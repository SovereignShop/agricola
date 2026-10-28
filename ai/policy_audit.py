"""Isolate real-state policy, choice values and imagined search on matched deals.

All variants share one immutable, canonically ordered training-only action pool.
Each game gets an independent RNG. All games are recorded and replay verified.
"""
import argparse
from concurrent.futures import ProcessPoolExecutor
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import torch
import muzero
from muzero import Environment, Replay, play
from enhancements import make_network
from deep_train import match_summary, paired_summary, atomic_json


class TrackedEnvironment(Environment):
    def __init__(self):
        super().__init__()
        self.previous = None
        self.model_seat = None
        self.choices = self.agreements = 0

    def request(self, **message):
        if message['op'] == 'reset':
            self.choices = self.agreements = 0
        if message['op'] == 'step' and self.previous and self.previous['player'] == self.model_seat:
            if len(self.previous['actions']) > 1:
                self.choices += 1
                self.agreements += message['action'] == self.previous.get('expert')
        response = super().request(**message)
        if message['op'] in ['reset', 'step']:
            self.previous = response
        return response


def evaluate(task):
    checkpoint, out, variant, seed, deals, simulations = task
    torch.set_num_threads(1)
    saved = torch.load(checkpoint, map_location='cpu', weights_only=False)
    net = make_network(saved['hidden'], saved['config']).eval()
    net.load_state_dict(saved['model'])
    if variant == 'guarded':
        if net.inference_mode != 'guarded-policy':raise ValueError('Use a hybrid checkpoint')
    else:
        net.inference_mode = 'search'
    catalog = saved['replay'].catalog
    frozen_pool = np.stack([catalog[key] for key in sorted(catalog)])
    original_search = muzero.search

    @torch.no_grad()
    def choose(net, observation, legal, actor, players, pool, rng, simulations=32, noise=False, depth_limit=8):
        if variant in ['search', 'search-depth1']:
            return original_search(net, observation, legal, actor, players, frozen_pool, rng,
                                   simulations, False, 1 if variant == 'search-depth1' else 8)
        hidden = net.representation(torch.from_numpy(observation))
        actions = torch.from_numpy(legal)
        if variant == 'policy':
            logits = net.policy(hidden, actions)
        elif variant == 'hint':
            logits = net.root_policy(hidden, actions, actor)
        elif variant == 'choice':
            logits = net.choice_value(hidden, actions)
        else:
            raise ValueError(variant)
        return torch.softmax(logits, -1).numpy()

    muzero.search = choose
    out = Path(out) / variant
    out.mkdir(parents=True, exist_ok=False)
    env = TrackedEnvironment()
    results = []
    started = time.monotonic()
    try:
        actual = env.request(op='schema')['fingerprints']
        actual['encoder'] = hashlib.sha256(Path(__file__).with_name('encoding.py').read_bytes()).hexdigest()
        if actual != saved['config']['fingerprints']:
            raise ValueError('Checkpoint rules/action/encoding mismatch')
        for deal in range(deals):
            for seat in range(2):
                env.model_seat = seat
                policies = ['heuristic', 'heuristic']
                if variant != 'heuristic':policies[seat] = 'muzero'
                replay = Replay(); replay.catalog = dict(catalog)
                _, result = play(env, net, replay, seed=seed + deal, mode='random', players=2,
                    policy=policies, simulations=simulations,
                    rng=np.random.default_rng((seed + deal) * 2 + seat),
                    record=(out / f'deal-{deal:03d}-seat-{seat}.edn').resolve())
                result.update(model_seat=seat, choices=env.choices, expert_agreements=env.agreements)
                results.append(result)
                atomic_json(out / 'status.json', {'variant': variant, 'completed': len(results),
                                                  'planned': deals * 2, 'summary': match_summary(results)})
        report = {'variant': variant, 'checkpoint': str(checkpoint), 'seed': seed, 'deals': deals,
                  'simulations': simulations, 'action_pool_sha256': hashlib.sha256(frozen_pool.tobytes()).hexdigest(),
                  'action_pool_size': len(frozen_pool), 'results': results, 'summary': match_summary(results),
                  'expert_agreement': sum(r['expert_agreements'] for r in results) / max(1, sum(r['choices'] for r in results)),
                  'elapsed_seconds': time.monotonic() - started}
        atomic_json(out / 'report.json', report)
        return report
    finally:
        muzero.search = original_search
        env.close()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--checkpoint', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--seed', type=int, default=8100000)
    p.add_argument('--deals', type=int, default=4)
    p.add_argument('--simulations', type=int, default=32)
    p.add_argument('--workers', type=int, default=3)
    p.add_argument('--variants', nargs='+', choices=['search','search-depth1','policy','hint','choice','guarded','heuristic'],
                   default=['search','search-depth1','policy','choice'])
    args = p.parse_args()
    if min(args.deals, args.simulations, args.workers) < 1:
        p.error('Counts must be positive')
    args.output.mkdir(parents=True, exist_ok=False)
    tasks = [(args.checkpoint.resolve(), args.output.resolve(), variant, args.seed, args.deals, args.simulations)
             for variant in args.variants]
    with ProcessPoolExecutor(max_workers=args.workers) as executor:
        reports = list(executor.map(evaluate, tasks))
    assert len({r['action_pool_sha256'] for r in reports}) == 1
    report = {r['variant']: {key: value for key, value in r.items() if key != 'results'} for r in reports}
    if 'search' in args.variants:
        baseline = next(r['results'] for r in reports if r['variant'] == 'search')
        for r in reports:
            report[r['variant']]['paired_vs_search'] = paired_summary(baseline, r['results'])
    atomic_json(args.output / 'report.json', report)
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
