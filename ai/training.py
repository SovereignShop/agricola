"""Batched recurrent learner, with variable legal-action lists masked per state.

Sampling and per-example loss weights match the serial reference learner. Only
real trajectory positions enter the losses; terminal states get zero value and
terminal=true targets, and recurrent gradients are halved only on continuation.
"""
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F


def sample_sequences(replay, rng, batch_size, unroll, card_fraction=0.):
    if not replay.games or batch_size < 1 or unroll < 0:
        raise ValueError("Training requires experience, a positive batch, and a nonnegative unroll")
    pool = replay.pool()
    card_positions = [(game, i) for game in replay.games for i, step in enumerate(game)
                      if step.get("card_choice")] if card_fraction else []
    sequences = []
    for _ in range(batch_size):
        if card_positions and rng.random() < card_fraction:
            game, start = card_positions[int(rng.integers(len(card_positions)))]
        else:
            game = replay.games[int(rng.integers(len(replay.games)))]
            start = int(rng.integers(len(game)))
        sequence = []
        for offset in range(unroll + 1):
            if start + offset >= len(game):
                break
            step = game[start + offset]
            negatives = pool[rng.choice(len(pool), min(16, len(pool)), replace=False)]
            negatives = [x for x in negatives if not np.any(np.all(step["actions"] == x, axis=1))]
            sequence.append((step, negatives))
            if offset < unroll and step["terminal"]:
                break
        sequences.append(sequence)
    return sequences


def padded_actions(arrays, device):
    lengths = [len(a) for a in arrays]
    # Empty negative-action lists still need a correctly shaped tensor.
    width = max(1, max(lengths))
    dim = next(a.shape[-1] for a in arrays if len(a))
    values = np.zeros((len(arrays), width, dim), dtype=np.float32)
    valid = np.zeros((len(arrays), width), dtype=bool)
    for row, actions in enumerate(arrays):
        if len(actions):
            values[row, :len(actions)] = actions
            valid[row, :len(actions)] = True
    return torch.as_tensor(values, device=device), torch.as_tensor(valid, device=device)


def train_batch(net, optimizer, replay, rng, batch_size=16, unroll=3):
    net.train()
    device = next(net.parameters()).device
    tensor = lambda x, dtype=torch.float32: torch.as_tensor(np.asarray(x), dtype=dtype, device=device)
    sequences = sample_sequences(replay, rng, batch_size, unroll, getattr(net, "card_sample_fraction", 0.))
    hidden = net.representation(tensor([s[0][0]["observation"] for s in sequences]))
    losses = {name: [] for name in ["policy", "value", "reward", "actor", "terminal", "legality"]}
    enhanced = hasattr(net, "choice_value")
    if enhanced:
        losses.update(choice_value=[], consistency=[])
    for offset in range(unroll + 1):
        steps = [s[offset][0] for s in sequences]
        actions, valid = padded_actions([s["actions"] for s in steps], device)
        counts = valid.sum(-1)
        policy_targets = np.zeros(valid.shape, dtype=np.float32)
        for row, step in enumerate(steps):
            policy_targets[row, :len(step["policy"])] = step["policy"]
        logits = net.policy(hidden, actions).masked_fill(~valid, -1e9)
        losses["policy"].append(-(tensor(policy_targets) * F.log_softmax(logits, -1)).sum(-1))
        players = tensor([s["players"] for s in steps])
        seat_mask = torch.arange(net.value.out_features, device=device)[None, :] < players[:, None]
        value_error = (net.value(hidden) - tensor([s["value"] for s in steps])).square()
        losses["value"].append((value_error * seat_mask).sum(-1) / players)
        if enhanced:
            chosen = tensor([s["action"] for s in steps]).unsqueeze(1)
            predicted = net.choice_value(hidden, chosen).squeeze(1)
            target = tensor([s["value"][s["actor"]] for s in steps])
            losses["choice_value"].append(F.smooth_l1_loss(predicted, target, reduction="none"))
        losses["actor"].append(F.cross_entropy(net.actor(hidden), tensor([s["actor"] for s in steps], torch.long), reduction="none"))
        terminal_logits = net.terminal(hidden).squeeze(-1)
        losses["terminal"].append(F.binary_cross_entropy_with_logits(terminal_logits, torch.zeros_like(terminal_logits), reduction="none"))
        losses["legality"].append((F.softplus(-net.legality(hidden, actions)) * valid).sum(-1) / counts)
        negatives = [s[offset][1] for s in sequences]
        rows = [i for i, values in enumerate(negatives) if len(values)]
        if rows:
            negative_actions, mask = padded_actions([np.asarray(negatives[i]) for i in rows], device)
            losses["legality"].append((F.softplus(net.legality(hidden[rows], negative_actions)) * mask).sum(-1) / mask.sum(-1))
        if offset == unroll:
            break
        next_hidden, reward = net.recurrent(hidden, tensor([s["action"] for s in steps]))
        # The serial learner scales every gradient through a nonterminal next
        # latent, including its reward head. Terminal next latents stay unscaled.
        gradient_scale = tensor([1. if s["terminal"] else .5 for s in steps])[:, None]
        next_hidden.register_hook(lambda grad, scale=gradient_scale: grad * scale)
        reward_error = (reward - tensor([s["reward"] for s in steps])).square()
        losses["reward"].append((reward_error * seat_mask).sum(-1) / players)
        terminal_rows = [i for i, step in enumerate(steps) if step["terminal"]]
        if terminal_rows:
            terminal_hidden = next_hidden[terminal_rows]
            terminal_logits = net.terminal(terminal_hidden).squeeze(-1)
            losses["terminal"].append(F.binary_cross_entropy_with_logits(terminal_logits, torch.ones_like(terminal_logits), reduction="none"))
            losses["value"].append((net.value(terminal_hidden).square() * seat_mask[terminal_rows]).sum(-1) / players[terminal_rows])
        continuing = [i for i, sequence in enumerate(sequences)
                      if not steps[i]["terminal"] and len(sequence) > offset + 1]
        if enhanced:
            # A different seat's next observation contains private cards that
            # this actor cannot know. Match latents only across the same actor.
            same_actor = [i for i in continuing if steps[i]["actor"] == sequences[i][offset + 1][0]["actor"]]
            if same_actor:
                with torch.no_grad():
                    actual = net.representation(tensor([sequences[i][offset + 1][0]["observation"] for i in same_actor]))
                losses["consistency"].append((next_hidden[same_actor] - actual).square().mean(-1))
        if not continuing:
            break
        hidden = next_hidden[continuing]
        sequences = [sequences[i] for i in continuing]
    means = {name: torch.cat(values).mean() if values else hidden.new_zeros(()) for name, values in losses.items()}
    loss = means["policy"] + means["value"] + means["reward"] + .2 * means["actor"] + .1 * means["terminal"] + .1 * means["legality"]
    if enhanced:
        loss = loss + net.choice_weight * means["choice_value"] + net.consistency_weight * means["consistency"]
    if not torch.isfinite(loss):
        raise RuntimeError("Nonfinite training loss")
    optimizer.zero_grad(set_to_none=True)
    loss.backward()
    nn.utils.clip_grad_norm_(net.parameters(), 5.)
    optimizer.step()
    net.eval()
    return {name: round(value.item(), 6) for name, value in means.items()}
