"""Learn family-growth investment value from paired native continuations.

Keep the deployed model frozen. Split independent positions/deals, fit actual
score differences, select on development, and test against the stronger parent.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import traceback
import numpy as np
from afterstate_value import Worker as BaseWorker
from card_expectations import paired, summary
from overnight import freeze, atomic

ROOT = Path(__file__).resolve().parents[1]


class Worker(BaseWorker):
    def __init__(self, root):
        self.process = subprocess.Popen(
            ['clojure', '-J-Xmx1g', '-J-XX:ActiveProcessorCount=2', '-M', '-m', 'agricola.growth-learning'],
            cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)


def design(rows, train):
    names = sorted({k for i in train for side in ['a', 'b'] for k in rows[i][side]['features']})
    matrix = np.array([[r['a']['features'].get(k, 0.) - r['b']['features'].get(k, 0.)
                        for k in names] for r in rows], dtype=float)
    parent = np.array([r['a']['parent_score'] - r['b']['parent_score'] for r in rows])
    outcomes = np.array([r['score_difference'] for r in rows], dtype=float)
    return names, matrix, outcomes - parent, parent, outcomes


def fit(rows, train, held, schema):
    names, x, y, parent, outcomes = design(rows, train)
    candidates = []
    for penalty in [.001, .01, .1, 1., 10.]:
        coef = np.linalg.solve(x[train].T @ x[train] + penalty * np.eye(len(names)), x[train].T @ y[train])
        rmse = float(np.sqrt(np.mean((x[held] @ coef - y[held]) ** 2)))
        candidates.append((rmse, penalty, coef))
    rmse, penalty, coef = min(candidates, key=lambda c: c[0])
    model = {**schema, 'weights': dict(zip(names, map(float, coef))), 'intercept': 0.,
             'penalty': penalty, 'validation_rmse': rmse,
             'interpretation': 'Correction to parent action-score differences, learned from paired final own scores.'}
    diagnostics = {'training_pairs': len(train), 'validation_pairs': len(held), 'features': len(names),
                   'parent_validation_rmse': float(np.sqrt(np.mean(y[held] ** 2))),
                   'learned_validation_rmse': rmse,
                   'penalties': [{'penalty': p, 'validation_rmse': e} for e, p, _ in candidates],
                   'by_category': {category: {'pairs': sum(r['category'] == category for r in rows),
                       'mean_actual_score_difference': float(np.mean([r['score_difference'] for r in rows if r['category'] == category]))}
                       for category in sorted({r['category'] for r in rows})}}
    return model, diagnostics


def behavior(games):
    return {'mean_first_growth_round': float(np.mean([r['first_growth'][r['model_seat']] for r in games])),
            'first_growth_by_round_6': sum(r['first_growth'][r['model_seat']] <= 6 for r in games),
            'never_grew': sum(r['first_growth'][r['model_seat']] == 15 for r in games),
            'mean_final_family': float(np.mean([r['families'][r['model_seat']] for r in games]))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cases', type=int, default=192)
    parser.add_argument('--workers', type=int, default=6)
    parser.add_argument('--dev-deals', type=int, default=8)
    parser.add_argument('--test-deals', type=int, default=24)
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    source = output / 'source-baseline'; freeze(source)
    status = {'pid': os.getpid(), 'started': time.time(), 'source': str(source), 'smoke_only': args.smoke}
    def update(stage, **extra):
        status.update(status=stage, **extra); atomic(output / 'status.json', status)
        print(json.dumps({'status': stage, **extra}), flush=True)
    workers = []; local = threading.local(); lock = threading.Lock()
    def worker():
        if not hasattr(local, 'worker'):
            local.worker = Worker(source)
            with lock: workers.append(local.worker)
        return local.worker
    def retire():
        main_worker = getattr(local, 'worker', None)
        for w in list(workers):
            if w is not main_worker: w.close(); workers.remove(w)
    try:
        update('paired-continuations', completed=0, cases=args.cases)
        # The position split is fixed before seeing any outcomes; both branches
        # and the source trajectory always belong to the same partition.
        order = np.random.default_rng(950019).permutation(args.cases)
        held_ids = set(map(int, order[:max(2, args.cases // 5)]))
        atomic(output / 'plan.json', {'training_case_indices': [i for i in range(args.cases) if i not in held_ids],
               'validation_case_indices': sorted(held_ids), 'source_seeds': [9500000, 9500000 + args.cases - 1],
               'development_seeds': [9600000, 9600000 + args.dev_deals - 1],
               'test_random_seeds': [9700000, 9700000 + args.test_deals - 1],
               'test_draw_seeds': [9800000, 9800000 + args.test_deals - 1],
               'selection': 'Highest development mean score margin; include parent and wider-filter-only controls',
               'target': 'Final own-score difference under identical frozen-parent continuations; no growth bonus',
               'promotion': 'Positive paired score and margin confidence intervals versus the deployed stronger parent in both modes'})
        samples = []
        def sample(i):
            category = ['grow', 'room', 'prepare'][i % 3]
            target = 5 + (i // 12) % 4 if category == 'grow' else 2 + (i // 12) % 4
            result = worker().request(op='sample', seed=9500000 + i, focal=(i // 3) % 2,
                         category=category, target_round=target, mode='random' if (i // 6) % 2 == 0 else 'draw',
                         output=str(output / 'samples' / str(i)))
            return {'case': i, 'partition': 'validation' if i in held_ids else 'training', **result}
        with (output / 'samples.jsonl').open('w') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(sample, i) for i in range(args.cases)]
            for i, future in enumerate(as_completed(futures), 1):
                row = future.result(); samples.append(row)
                log.write(json.dumps(row) + '\n'); log.flush()
                if i % 8 == 0 or i == args.cases:
                    update('paired-continuations', completed=i, cases=args.cases,
                           usable_pairs=sum(not r.get('skipped') for r in samples))
        retire()
        rows = sorted([r for r in samples if not r.get('skipped')], key=lambda r: r['case'])
        train = [i for i, row in enumerate(rows) if row['partition'] == 'training']
        held = [i for i, row in enumerate(rows) if row['partition'] == 'validation']
        assert len(train) >= 3 and len(held) >= 2, 'Too few usable paired positions'
        update('fitting', pairs=len(rows))
        schema = worker().request(op='schema')
        model, diagnostics = fit(rows, train, held, schema)
        atomic(output / 'growth.json', model)
        report = {'schema': schema, 'smoke_only': args.smoke, 'fit': diagnostics,
                  'source_games': len(samples), 'paired_continuations': 2 * len(rows),
                  'rules_unchanged': True, 'parent': 'Deployed card-aware value model, not the older heuristic'}
        atomic(output / 'report.json', report)
        print(json.dumps({'fit': diagnostics}), flush=True)
        variants = {'parent': (1., 0.), 'wide-parent': (3., 0.),
                    'learned-narrow-0.5': (1., .5), 'learned-narrow-1.0': (1., 1.),
                    'learned-wide-0.5': (3., .5), 'learned-wide-1.0': (3., 1.)}
        def tournament(stage, policies, seed0, deals, mode):
            results = {name: [] for name in policies}
            jobs = [(name, seed, seat) for name in policies for seed in range(seed0, seed0 + deals) for seat in [0, 1]]
            update(stage, completed=0, games=len(jobs))
            def play(job):
                name, seed, seat = job; margin, strength = policies[name]
                row = worker().request(op='game', seed=seed, focal=seat, mode=mode, margin=margin, strength=strength,
                       model=str(output / 'growth.json') if strength else None,
                       output=str(output / 'recordings' / f'{stage}-{name}-{seed}-{seat}.edn'))
                return name, row
            with (output / 'games.jsonl').open('a') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
                futures = [pool.submit(play, j) for j in jobs]
                for i, future in enumerate(as_completed(futures), 1):
                    name, row = future.result(); results[name].append(row)
                    log.write(json.dumps({'stage': stage, 'variant': name, **row}) + '\n'); log.flush()
                    if i % 8 == 0 or i == len(jobs): update(stage, completed=i, games=len(jobs))
            retire()
            return {name: {'summary': summary(games), 'behavior': behavior(games),
                           'paired_vs_parent': paired(results['parent'], games)} for name, games in results.items()}
        report['development'] = tournament('development', variants, 9600000, args.dev_deals, 'random')
        selected = max(report['development'], key=lambda name: report['development'][name]['summary']['mean_margin'])
        report['selected'] = selected; atomic(output / 'report.json', report)
        if selected != 'parent':
            policies = {k: variants[k] for k in ['parent', selected]}
            report['test_random'] = tournament('test-random', policies, 9700000, args.test_deals, 'random')
            atomic(output / 'report.json', report)
            report['test_draw'] = tournament('test-draw', policies, 9800000, args.test_deals, 'draw')
            report['promotion_supported'] = not args.smoke and all(
                min(report[mode][selected]['paired_vs_parent'][metric][0]
                    for metric in ['score_gain_95_interval', 'margin_gain_95_interval']) > 0
                for mode in ['test_random', 'test_draw'])
        else:
            report['promotion_supported'] = False
        atomic(output / 'report.json', report)
        update('complete', selected=selected, promotion_supported=report['promotion_supported'])
    except BaseException as exc:
        update('failed', error=str(exc)); traceback.print_exc(); raise
    finally:
        for w in workers: w.close()


if __name__ == '__main__': main()
