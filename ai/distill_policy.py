"""Learn legal-action choices directly from retained strong demonstrations.

Game-level train/validation split; validation action likelihood selects a model.
No recurrent rollouts or self-generated policy targets enter this experiment.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import torch
from torch.nn import functional as F
from muzero import Replay, save_checkpoint
from enhancements import make_network
from deep_train import atomic_json


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--checkpoint', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--updates', type=int, default=6000)
    parser.add_argument('--batch-size', type=int, default=192)
    parser.add_argument('--seed', type=int, default=8600000)
    parser.add_argument('--learning-rate', type=float, default=.0003)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    torch.set_num_threads(1); torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    saved = torch.load(args.checkpoint, map_location='cpu', weights_only=False)
    if saved['config'].get('reward_shaping', 'none') != 'none':
        raise ValueError('Use raw-reward demonstrations for this experiment')
    games = saved['replay'].demonstrations
    if len(games) < 32:
        raise ValueError('At least 32 independent demonstration games required')
    # Reject self-play/search distributions: these targets must be actual teacher moves.
    if any(np.count_nonzero(s['policy']) != 1 for game in games for s in game):
        raise ValueError('Demonstrations must contain one-hot teacher actions')
    order = rng.permutation(len(games)); validation_games = set(order[:max(16, len(games)//5)].tolist())
    steps, train, validation, early = [], [], [], []
    for game_index, game in enumerate(games):
        for ply, step in enumerate(game):
            if len(step['actions']) < 2:
                continue
            index = len(steps); steps.append(step)
            (validation if game_index in validation_games else train).append(index)
            if game_index not in validation_games and ply < 36:
                early.append(index)
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    config = dict(saved['config'], inference_mode='policy', training_algorithm='expert-distillation-v1',
                  parent_checkpoint=str(args.checkpoint.resolve()),
                  parent_sha256=hashlib.sha256(args.checkpoint.read_bytes()).hexdigest(),
                  distillation_seed=args.seed, learning_rate=args.learning_rate,
                  demonstration_games=len(games), validation_game_indices=sorted(validation_games),
                  learner_source_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  selection='held-out demonstration action cross-entropy; no evaluation games used',
                  early_sampling_fraction=.35)
    net = make_network(saved['hidden'], config).to(device)
    net.load_state_dict(saved['model'])
    optimizer = torch.optim.AdamW(net.parameters(), lr=args.learning_rate, weight_decay=1e-4)
    vectors, by_bytes = [], {}
    def action_index(vector):
        key = vector.tobytes()
        if key not in by_bytes:
            by_bytes[key] = len(vectors); vectors.append(vector)
        return by_bytes[key]
    width = max(len(step['actions']) for step in steps)
    indices = np.zeros((len(steps), width), dtype=np.int64)
    mask = np.zeros(indices.shape, dtype=bool)
    for i, step in enumerate(steps):
        n = len(step['actions'])
        indices[i,:n] = [action_index(a) for a in step['actions']]
        mask[i,:n] = True
    observations = torch.as_tensor(np.stack([s['observation'] for s in steps]), device=device)
    targets = torch.tensor([int(np.argmax(s['policy'])) for s in steps], device=device)
    actions = torch.as_tensor(np.stack(vectors), device=device)
    indices = torch.as_tensor(indices, device=device); mask = torch.as_tensor(mask, device=device)
    # Preserve exactly the parent's proposal pool for comparisons; no held-out
    # observations or targets enter the saved training replay.
    replay = Replay(capacity=len(games))
    replay.catalog = dict(saved['replay'].catalog)
    replay.games = [g for i,g in enumerate(games) if i not in validation_games]
    replay.demonstrations = replay.games
    atomic_json(args.output/'config.json', config)
    log = (args.output/'metrics.jsonl').open('w')
    started = time.monotonic(); best = float('inf')

    def logits(rows):
        local_mask = mask[rows]
        # Trim to this minibatch's legal-action count to avoid unnecessary work.
        n = int(local_mask.sum(-1).max())
        return net.policy(net.representation(observations[rows]), actions[indices[rows,:n]]).masked_fill(~local_mask[:,:n], -1e9)

    @torch.no_grad()
    def validate():
        net.eval(); loss, correct, count = 0., 0, 0
        for start in range(0,len(validation),args.batch_size):
            rows = validation[start:start+args.batch_size]
            prediction = logits(rows)
            loss += float(F.cross_entropy(prediction,targets[rows],reduction='sum'))
            correct += int((prediction.argmax(-1)==targets[rows]).sum()); count += len(rows)
        return loss/count, correct/count

    try:
        for update in range(args.updates+1):
            if update:
                net.train()
                n = int(args.batch_size*.35)
                rows = np.concatenate([rng.choice(early,n),rng.choice(train,args.batch_size-n)]).tolist()
                loss = F.cross_entropy(logits(rows),targets[rows])
                if not torch.isfinite(loss):raise RuntimeError('Nonfinite policy loss')
                optimizer.zero_grad(set_to_none=True);loss.backward()
                torch.nn.utils.clip_grad_norm_(net.parameters(),5.)
                optimizer.step()
            if update % 500 == 0 or update == args.updates:
                valid_loss, accuracy = validate()
                event = {'updates':update,'validation_loss':valid_loss,'validation_accuracy':accuracy,
                         'training_positions':len(train),'validation_positions':len(validation),
                         'device':device,'elapsed_seconds':time.monotonic()-started}
                if valid_loss < best:
                    best = valid_loss;event['selected'] = True
                    save_checkpoint(args.output/'best.pt',net,optimizer,replay,rng,config,update)
                log.write(json.dumps(event)+'\n');log.flush()
                atomic_json(args.output/'status.json',event);print(json.dumps(event),flush=True)
        save_checkpoint(args.output/'latest.pt',net,optimizer,replay,rng,config,args.updates)
        atomic_json(args.output/'report.json',dict(event,status='complete',best_validation_loss=best))
    finally:
        log.close()


if __name__ == '__main__':main()
