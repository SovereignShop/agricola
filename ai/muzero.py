"""Sampled-action MuZero for the production Agricola engine.

The tree uses learned dynamics only, root actions are engine-validated, and
interior actions are sampled from commands encountered in experience. Absolute
seat value vectors avoid the incorrect alternating-turn sign flip in Agricola.
"""
from __future__ import annotations
import argparse
import json
import math
import os
from pathlib import Path
import random
import subprocess
import time
from dataclasses import dataclass, field
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from encoding import encode, VERSION as ENCODER, OBS_DIM, ACTION_DIM, SEATS, HIDDEN, ACTION_SPACE
import hashlib
from training import train_batch

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ACTION_SPACE


class Environment:
    def __init__(self):
        env = dict(os.environ)
        if not env.get("JAVA_HOME"):
            fallback = Path("/home/john/clj-manifold3d-demo/vendor/runtime/usr/lib/jvm/java-21-openjdk-amd64")
            if fallback.exists():
                env["JAVA_HOME"] = str(fallback)
        self.process = subprocess.Popen(["clojure", "-J-Xmx2g", "-M:ai"], cwd=ROOT, env=env,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)

    def request(self, **message):
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError(f"Environment exited: {self.process.poll()}")
        result = json.loads(line)
        if "error" in result:
            raise RuntimeError(result["error"])
        return result

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=10)


class Network(nn.Module):
    def __init__(self, hidden=HIDDEN):
        super().__init__()
        self.hidden = hidden
        self.representation = nn.Sequential(nn.Linear(OBS_DIM, hidden), nn.ReLU(), nn.Linear(hidden, hidden), nn.LayerNorm(hidden), nn.Tanh())
        self.action = nn.Sequential(nn.Linear(ACTION_DIM, hidden), nn.ReLU(), nn.Linear(hidden, hidden), nn.Tanh())
        self.dynamics = nn.Sequential(nn.Linear(2 * hidden, hidden), nn.ReLU(), nn.Linear(hidden, hidden), nn.LayerNorm(hidden), nn.Tanh())
        self.query = nn.Linear(hidden, hidden)
        self.legal_query = nn.Linear(hidden, hidden)
        self.value = nn.Linear(hidden, SEATS)
        self.reward = nn.Linear(hidden, SEATS)
        self.actor = nn.Linear(hidden, SEATS)
        self.terminal = nn.Linear(hidden, 1)

    def policy(self, hidden, actions):
        encoded = self.action(actions)
        query = self.query(hidden)
        scores = encoded @ query if hidden.ndim == 1 else torch.bmm(encoded, query.unsqueeze(-1)).squeeze(-1)
        return scores / math.sqrt(self.hidden)

    def legality(self, hidden, actions):
        encoded, query = self.action(actions), self.legal_query(hidden)
        scores = encoded @ query if hidden.ndim == 1 else torch.bmm(encoded, query.unsqueeze(-1)).squeeze(-1)
        return scores / math.sqrt(self.hidden)

    def recurrent(self, hidden, action):
        next_hidden = self.dynamics(torch.cat([hidden, self.action(action)], dim=-1))
        return next_hidden, self.reward(next_hidden)


@dataclass
class Node:
    hidden: torch.Tensor
    actor: int
    actions: np.ndarray
    priors: np.ndarray
    terminal: bool = False
    visits: np.ndarray = field(init=False)
    totals: np.ndarray = field(init=False)
    children: dict = field(default_factory=dict)

    def __post_init__(self):
        self.visits = np.zeros(len(self.priors), dtype=np.int32)
        self.totals = np.zeros((len(self.priors), SEATS), dtype=np.float32)


def backup(path, value, discount=1.0):
    for node, index, reward in reversed(path):
        value = reward + discount * value
        node.visits[index] += 1
        node.totals[index] += value


@torch.no_grad()
def search(net, observation, legal, actor, players, pool, rng, simulations=16, noise=False, depth_limit=8):
    if getattr(net, 'inference_mode', 'search') == 'guarded-policy':
        raise ValueError('Hybrid policy requires real-state guidance; use guarded_policy')
    hidden = net.representation(torch.from_numpy(observation))
    legal_tensor = torch.from_numpy(legal)
    if getattr(net, 'inference_mode', 'search') == 'policy':
        # A distilled policy uses real legal actions only. Its old dynamics/value
        # heads are not retrained and must not be used for imagined rollouts.
        return torch.softmax(net.policy(hidden, legal_tensor), dim=-1).numpy()
    logits = net.root_policy(hidden, legal_tensor, actor) if hasattr(net, "root_policy") else net.policy(hidden, legal_tensor)
    priors = torch.softmax(logits, dim=-1).numpy()
    if noise:
        priors = .75 * priors + .25 * rng.dirichlet(np.full(len(legal), .3))
    root = Node(hidden, actor, legal, priors)
    # The engine is not called during search. Sample interior proposals once per search.
    proposals = pool if len(pool) else legal
    if len(proposals) > 48:
        proposals = proposals[rng.choice(len(proposals), 48, replace=False)]
    proposal_tensor = torch.from_numpy(proposals)
    for _ in range(simulations):
        node, path = root, []
        for depth in range(depth_limit):
            if node.terminal:
                value = np.zeros(SEATS, dtype=np.float32)
                break
            means = node.totals[:, node.actor] / np.maximum(1, node.visits)
            # Normalize Q to balance score-scale predictions against PUCT priors.
            visited = node.visits > 0
            if visited.any():
                lo, hi = min(0., means[visited].min()), max(.01, means[visited].max())
                means = (means - lo) / max(.01, hi - lo)
            bonus = 1.5 * node.priors * math.sqrt(1 + node.visits.sum()) / (1 + node.visits)
            index = int(np.argmax(means + bonus))
            if index not in node.children:
                next_hidden, reward = net.recurrent(node.hidden, torch.from_numpy(node.actions[index]))
                next_actor = int(net.actor(next_hidden)[:players].argmax())
                logits = net.policy(next_hidden, proposal_tensor) + F.logsigmoid(net.legality(next_hidden, proposal_tensor))
                terminal = bool(torch.sigmoid(net.terminal(next_hidden)).item() > .9)
                child = Node(next_hidden, next_actor, proposals, torch.softmax(logits, -1).numpy(), terminal)
                reward = reward.numpy()
                node.children[index] = (child, reward)
                path.append((node, index, reward))
                value = np.zeros(SEATS, dtype=np.float32) if terminal else net.value(next_hidden).numpy()
                break
            child, reward = node.children[index]
            path.append((node, index, reward))
            node = child
        else:
            value = net.value(node.hidden).numpy()
        backup(path, value)
    counts = root.visits.astype(np.float32)
    return counts / counts.sum() if counts.sum() else priors


@torch.no_grad()
def guarded_policy(net, observation, legal, advice):
    """Neural ranking within a bounded gap of the public-state heuristic.

    This is a hybrid policy, not an improvement claim for the network alone.
    Heuristic estimates are not a guarantee about final game score.
    """
    if not advice or advice.get('fingerprint') != net.guidance_fingerprint or not net.guidance_fingerprint:
        raise ValueError('Hybrid checkpoint and engine guidance do not match')
    values = np.asarray(advice['values'], dtype=np.float32)
    if values.shape != (len(legal),) or not np.isfinite(values).all() or not 0 <= net.guidance_margin <= 10:
        raise ValueError('Invalid action guidance')
    allowed = values >= values.max() - net.guidance_margin
    hidden = net.representation(torch.from_numpy(observation))
    logits = net.policy(hidden, torch.from_numpy(legal))
    return torch.softmax(logits.masked_fill(~torch.from_numpy(allowed), -1e9), -1).numpy()


class Replay:
    def __init__(self, capacity=120):
        self.games = []
        self.capacity = capacity
        self.catalog = {}

    def remember(self, actions):
        for action in actions:
            if action["id"] not in self.catalog:
                self.catalog[action["id"]] = encode(action["features"], ACTION_DIM)

    def add(self, trajectory):
        self.games.append(trajectory)
        self.games = self.games[-self.capacity:]

    def pool(self):
        return np.stack(list(self.catalog.values())) if self.catalog else np.empty((0, ACTION_DIM), np.float32)


def is_card_choice(action):
    # EDN feature paths include nested choices such as Sidekick's action-command.
    return any(any(part in name.split() for part in [":card", ":improvement", ":card-space"])
               or name.startswith(("[:card ", "[:improvement ", "[:card-space "))
               for name, _ in action["features"])


def reencode(env, replay, source, destination):
    """Rebuild tensors/targets from verified EDN; no old encoded cache is trusted."""
    response = env.request(op="load-recording", path=str(source.resolve()), record=str(destination.resolve()))
    trajectory = []
    while not response["terminal"]:
        replay.remember(response["actions"])
        selected = response["recorded-action"]
        if selected is None:
            raise ValueError(f"Recorded move {response['ply']} is outside this macro action space: {source}")
        actions = np.stack([replay.catalog[a["id"]] for a in response["actions"]])
        before = response
        response = env.request(op="step", action=selected)
        reward = np.zeros(SEATS, dtype=np.float32)
        reward[:before["players"]] = response["reward"]
        trajectory.append({"observation": encode(before["observation"], OBS_DIM), "actions": actions,
                           "action": actions[selected], "policy": np.eye(len(actions), dtype=np.float32)[selected],
                           "reward": reward, "actor": before["player"], "players": before["players"],
                           "terminal": response["terminal"], "selected": selected,
                           "card_choice": is_card_choice(before["actions"][selected])})
    value = np.zeros(SEATS, dtype=np.float32)
    for step in reversed(trajectory):
        value = step["reward"] + value
        step["value"] = value.copy()
    verified = env.request(op="replay", path=str(destination.resolve()))
    if verified["status"] != "complete":
        raise RuntimeError("Re-encoded recording failed verification")
    return trajectory, response["scores"]


def play(env, net, replay, *, seed, mode, players, policy, simulations, rng, record, training=False, max_moves=1500):
    needs_expert = "heuristic" in policy if isinstance(policy, list) else policy == "heuristic"
    guided = getattr(net, 'inference_mode', 'search') == 'guarded-policy'
    response = env.request(op="reset", seed=seed, players=players, mode=mode, record=str(record), expert=needs_expert, guidance=guided)
    trajectory, scores, started = [], None, time.monotonic()
    behavior={'growth_rounds':[[] for _ in range(players)],'begging':[0]*players,'family':[2]*players,'first_cooking':[None]*players,
              'food_consumed':[0]*players,'net_food_income':[0]*players,'placements':[0]*players,'harvests':[[] for _ in range(players)]}
    for _ in range(max_moves):
        if response["terminal"]:
            scores = response["scores"]
            break
        assert response["version"] == SCHEMA
        replay.remember(response["actions"])
        obs = encode(response["observation"], OBS_DIM)
        actions = np.stack([replay.catalog[a["id"]] for a in response["actions"]])
        seat_policy = policy[response["player"]] if isinstance(policy, list) else policy
        if seat_policy == "heuristic":
            selected = response["expert"]
            target = np.eye(len(actions), dtype=np.float32)[selected]
        elif seat_policy == "random":
            selected = int(rng.integers(len(actions)))
            target = np.full(len(actions), 1. / len(actions), dtype=np.float32)
        else:
            target = (guarded_policy(net, obs, actions, response.get('guidance')) if guided else
                      search(net, obs, actions, response["player"], players, replay.pool(), rng, simulations, noise=training))
            selected = int(rng.choice(len(target), p=target)) if training else int(np.argmax(target))
        before = response
        response = env.request(op="step", action=selected, expert=needs_expert, guidance=guided)
        for pid,(old,new) in enumerate(zip(before.get('learning',{}).get('metrics',[]),response.get('learning',{}).get('metrics',[]))):
            behavior['growth_rounds'][pid]+=[before['round']]*max(0,new['family']-old['family'])
            behavior['begging'][pid]=new['begging'];behavior['family'][pid]=new['family']
            if new['cooking-access']>0 and behavior['first_cooking'][pid] is None:behavior['first_cooking'][pid]=before['round']
            consumed=0
            if new['fed'] and not old['fed']:
                begged=new['begging']-old['begging'];consumed=old['required']-begged
                behavior['harvests'][pid].append({'round':before['round'],'required':old['required'],'begging':begged,'consumed':consumed})
            behavior['food_consumed'][pid]+=consumed
            behavior['net_food_income'][pid]+=new['food']-old['food']+consumed
            if pid==before['player'] and before['phase']=='work':
                behavior['placements'][pid]+=old['remaining'] if response['round']>before['round'] else max(0,old['remaining']-new['remaining'])
        reward = np.zeros(SEATS, dtype=np.float32)
        reward[:players] = response["reward"]
        trajectory.append({"observation": obs, "actions": actions, "action": actions[selected], "policy": target,
                           "reward": reward, "actor": before["player"], "players": players,
                           "terminal": response["terminal"], "selected": selected,
                           "card_choice": is_card_choice(before["actions"][selected])})
    if scores is None and response["terminal"]:
        scores = response["scores"]
    if scores is None:
        raise RuntimeError(f"Episode hit {max_moves} moves; partial recording: {record}")
    value = np.zeros(SEATS, dtype=np.float32)
    for step in reversed(trajectory):
        value = step["reward"] + value
        step["value"] = value.copy()
    result = env.request(op="replay", path=str(record))
    if result["status"] != "complete" or result["moves"] != len(trajectory):
        raise RuntimeError(f"Replay failed: {result}")
    return trajectory, {"seed": seed, "policy": policy, "scores": scores, "moves": len(trajectory),
                        "seconds": round(time.monotonic() - started, 2), "replay": result["status"], "recording": str(record),
                        "inference_mode":getattr(net, 'inference_mode', 'search'),
                        **({'behavior':behavior} if 'learning' in response else {})}


def train_batch_serial(net, optimizer, replay, rng, batch_size=16, unroll=3):
    net.train()
    losses = {name: [] for name in ["policy", "value", "reward", "actor", "terminal", "legality"]}
    pool = replay.pool()
    for _ in range(batch_size):
        game = replay.games[int(rng.integers(len(replay.games)))]
        start = int(rng.integers(len(game)))
        hidden = net.representation(torch.from_numpy(game[start]["observation"]))
        for offset in range(unroll + 1):
            i = start + offset
            if i >= len(game):
                break
            step = game[i]
            actions = torch.from_numpy(step["actions"])
            logits = net.policy(hidden, actions)
            losses["policy"].append(-(torch.from_numpy(step["policy"]) * F.log_softmax(logits, -1)).sum())
            losses["value"].append(F.mse_loss(net.value(hidden)[:step["players"]], torch.from_numpy(step["value"][:step["players"]])))
            losses["actor"].append(F.cross_entropy(net.actor(hidden).unsqueeze(0), torch.tensor([step["actor"]])))
            losses["terminal"].append(F.binary_cross_entropy_with_logits(net.terminal(hidden).squeeze(), torch.tensor(0.)))
            negatives = pool[rng.choice(len(pool), min(16, len(pool)), replace=False)]
            # Do not label a sampled action negative if it is legal in this state.
            negatives = [x for x in negatives if not np.any(np.all(step["actions"] == x, axis=1))]
            losses["legality"].append(F.softplus(-net.legality(hidden, actions)).mean())
            if negatives:
                losses["legality"].append(F.softplus(net.legality(hidden, torch.from_numpy(np.stack(negatives)))).mean())
            if offset < unroll:
                hidden, reward = net.recurrent(hidden, torch.from_numpy(step["action"]))
                losses["reward"].append(F.mse_loss(reward[:step["players"]], torch.from_numpy(step["reward"][:step["players"]])))
                if step["terminal"]:
                    losses["terminal"].append(F.binary_cross_entropy_with_logits(net.terminal(hidden).squeeze(), torch.tensor(1.)))
                    losses["value"].append(net.value(hidden)[:step["players"]].square().mean())
                    break
                hidden.register_hook(lambda grad: grad * .5)
    means = {name: torch.stack(values).mean() if values else torch.tensor(0.) for name, values in losses.items()}
    loss = means["policy"] + means["value"] + means["reward"] + .2 * means["actor"] + .1 * means["terminal"] + .1 * means["legality"]
    if not torch.isfinite(loss):
        raise RuntimeError("Nonfinite training loss")
    optimizer.zero_grad()
    loss.backward()
    nn.utils.clip_grad_norm_(net.parameters(), 5.)
    optimizer.step()
    net.eval()
    return {name: round(value.item(), 6) for name, value in means.items()}


def save_checkpoint(path, net, optimizer, replay, rng, config, updates):
    data = {"schema": SCHEMA, "encoder": ENCODER, "config": config, "updates": updates, "hidden": net.hidden,
            "model": net.state_dict(), "optimizer": optimizer.state_dict(), "replay": replay,
            "numpy_rng": rng.bit_generator.state, "torch_rng": torch.get_rng_state()}
    temp = path.with_suffix(".tmp")
    torch.save(data, temp)
    temp.replace(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=["beginner", "random", "draw"], default="random")
    parser.add_argument("--players", type=int, choices=range(1, 5), default=2)
    parser.add_argument("--seed", type=int, default=1000)
    parser.add_argument("--warmup-games", type=int, default=12)
    parser.add_argument("--selfplay-games", type=int, default=24)
    parser.add_argument("--pretrain-updates", type=int, default=200)
    parser.add_argument("--updates-per-game", type=int, default=15)
    parser.add_argument("--simulations", type=int, default=16)
    parser.add_argument("--eval-games", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=12)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--bootstrap-recordings", nargs="*", type=Path, default=[])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "recordings").mkdir()
    torch.set_num_threads(args.threads)
    torch.manual_seed(args.seed)
    random.seed(args.seed)
    rng = np.random.default_rng(args.seed)
    net = Network()
    optimizer = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-4)
    replay, updates = Replay(), 0
    if args.resume:
        # Checkpoints are local trusted training artifacts, not arbitrary uploads.
        saved = torch.load(args.resume, weights_only=False, map_location="cpu")
        if saved["schema"] != SCHEMA or saved.get("encoder") != ENCODER:
            raise ValueError("Action-space schema mismatch")
        if (saved["config"]["mode"], saved["config"]["players"]) != (args.mode, args.players):
            raise ValueError("Resume must use the checkpoint's mode and player count")
        net.load_state_dict(saved["model"])
        optimizer.load_state_dict(saved["optimizer"])
        replay, updates = saved["replay"], saved["updates"]
        rng.bit_generator.state = saved["numpy_rng"]
        torch.set_rng_state(saved["torch_rng"])
    net.eval()
    config = {k: [str(x) for x in v] if isinstance(v, list) else str(v) if isinstance(v, Path) else v
              for k, v in vars(args).items()}
    config.update({"schema": SCHEMA, "encoder": ENCODER, "torch": torch.__version__, "device": "cpu", "observation_dim": OBS_DIM, "action_dim": ACTION_DIM})
    env = Environment()
    config["fingerprints"] = env.request(op="schema")["fingerprints"]
    config["fingerprints"]["encoder"] = hashlib.sha256((Path(__file__).parent / "encoding.py").read_bytes()).hexdigest()
    if args.resume and saved["config"].get("fingerprints") != config["fingerprints"]:
        env.close()
        raise ValueError("Rules/action/encoder fingerprint changed. Revalidate recordings and start fresh training; stale replay targets cannot be resumed.")
    (args.output / "config.json").write_text(json.dumps(config, indent=2))
    report = {"config": config, "episodes": [], "evaluation": {}, "updates": updates}
    metrics = open(args.output / "metrics.jsonl", "w")

    def emit(value):
        metrics.write(json.dumps(value) + "\n")
        metrics.flush()
        print(json.dumps(value), flush=True)

    def episode(stage, index, policy, seed, train=False):
        record = (args.output / "recordings" / f"{stage}-{index:03d}.edn").resolve()
        # Evaluation cannot add even action proposals to the training catalog.
        experience = replay
        if stage not in ("warmup", "selfplay"):
            experience = Replay()
            experience.catalog = dict(replay.catalog)
        trajectory, result = play(env, net, experience, seed=seed, mode=args.mode, players=args.players,
                                  policy=policy, simulations=args.simulations, rng=rng, record=record, training=train)
        emit(dict(stage=stage, **result))
        report["episodes"].append(dict(stage=stage, **result))
        return trajectory, result

    try:
        for i, source in enumerate(args.bootstrap_recordings):
            destination = args.output / "recordings" / f"reencoded-{i:03d}.edn"
            trajectory, scores = reencode(env, replay, source, destination)
            if trajectory[0]["players"] != args.players:
                raise ValueError("Bootstrap recording player count does not match this run")
            replay.add(trajectory)
            emit({"stage": "reencoded", "source": str(source), "scores": scores, "moves": len(trajectory), "replay": "complete"})
        for i in range(args.warmup_games):
            trajectory, _ = episode("warmup", i, "heuristic" if i % 4 else "random", args.seed + i)
            replay.add(trajectory)
        if not replay.games:
            raise ValueError("At least one warmup game or a resumed replay buffer is required")
        # Fixed held-out seeds, shared by each policy. Evaluation does not enter the replay buffer.
        eval_seed = args.seed + 100000
        for policy in ["random", "heuristic", "untrained-search"]:
            results = [episode(policy, i, "muzero" if policy == "untrained-search" else policy, eval_seed + i)[1]
                       for i in range(args.eval_games)]
            report["evaluation"][policy] = results
        for i in range(args.pretrain_updates):
            loss = train_batch(net, optimizer, replay, rng, args.batch_size)
            updates += 1
            if (i + 1) % 25 == 0:
                emit({"stage": "pretrain", "updates": updates, "loss": loss})
        save_checkpoint(args.output / "latest.pt", net, optimizer, replay, rng, config, updates)
        for i in range(args.selfplay_games):
            trajectory, _ = episode("selfplay", i, "muzero", args.seed + 1000 + i, train=True)
            replay.add(trajectory)
            for _ in range(args.updates_per_game):
                loss = train_batch(net, optimizer, replay, rng, args.batch_size)
                updates += 1
            emit({"stage": "training", "updates": updates, "loss": loss})
            save_checkpoint(args.output / "latest.pt", net, optimizer, replay, rng, config, updates)
        report["evaluation"]["trained-search"] = [episode("trained-search", i, "muzero", eval_seed + i)[1]
                                                       for i in range(args.eval_games)]
        if args.players == 2:
            report["versus-heuristic"] = []
            for i in range(args.eval_games):
                for seat in range(2):
                    policies = ["heuristic", "heuristic"]
                    policies[seat] = "muzero"
                    result = episode("versus-heuristic", 2 * i + seat, policies, eval_seed + i)[1]
                    result["model_seat"] = seat
                    report["versus-heuristic"].append(result)
        report["updates"] = updates
        report["summary"] = {policy: {"games": len(results), "mean_score": float(np.mean([r["scores"] for r in results])) if results else None,
                                      "scores": [r["scores"] for r in results]} for policy, results in report["evaluation"].items()}
        (args.output / "report.json").write_text(json.dumps(report, indent=2))
        emit({"stage": "complete", "updates": updates, "summary": report["summary"]})
    finally:
        env.close()
        metrics.close()


if __name__ == "__main__":
    main()
