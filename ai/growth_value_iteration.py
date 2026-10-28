"""Refresh early-game card/family values using the stronger policy's returns.
Preserve the paired study's whole-deal split and the previous model as a prior.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import traceback
import numpy as np
import torch
from afterstate_value import Worker as BaseWorker
from card_expectations import summary, paired
from growth_learning import behavior
from overnight import freeze, atomic


class Worker(BaseWorker):
    def __init__(self, root):
        self.process = subprocess.Popen(
            ['clojure', '-J-Xmx1g', '-J-XX:ActiveProcessorCount=2', '-M', '-m', 'agricola.growth-value'],
            cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)


def fit(rows, train_cases, schema, output):
    train = np.array([i for i, r in enumerate(rows) if r['case'] in train_cases])
    held = np.array([i for i, r in enumerate(rows) if r['case'] not in train_cases])
    names = sorted({k for i in train for k in rows[i]['features']})
    names_index = {name: i for i, name in enumerate(names)}
    x = np.zeros((len(rows), len(names)), np.float32)
    for i, row in enumerate(rows):
        for name, number in row['features'].items():
            if name in names_index: x[i, names_index[name]] = number
    y = np.array([r['target'] - r['parent'] for r in rows], np.float32)
    positions_per_file = Counter(r['recording'] for r in rows)
    files_per_case = {}
    for row in rows: files_per_case.setdefault(row['case'], set()).add(row['recording'])
    weights = np.array([1. / (len(files_per_case[r['case']]) * positions_per_file[r['recording']]) for r in rows], np.float32)
    torch.set_num_threads(2)
    matrix = torch.tensor(x[train], device='cuda')
    weight = torch.tensor(weights[train], device='cuda')
    target = torch.tensor(y[train], device='cuda')
    gram = matrix.T @ (matrix * weight[:, None]); rhs = matrix.T @ (target * weight)
    identity = torch.eye(len(names), device='cuda')
    candidates = []
    for penalty in [.1, 1., 10., 100.]:
        coefficient = torch.linalg.solve(gram + penalty * identity, rhs).cpu().numpy()
        error = x[held] @ coefficient - y[held]
        rmse = float(np.sqrt(np.average(error ** 2, weights=weights[held])))
        candidates.append((rmse, penalty, coefficient))
    rmse, penalty, coefficient = min(candidates, key=lambda c: c[0])
    for strength, name in [(.5, 'half'), (1., 'full')]:
        learned = dict(schema['parent_weights'])
        for key, delta in zip(names, coefficient): learned[key] = learned.get(key, 0.) + strength * float(delta)
        atomic(output / f'{name}.json', {k: v for k, v in schema.items() if k != 'parent_weights'} |
               {'weights': learned, 'intercept': 0., 'penalty': penalty, 'correction_strength': strength,
                'interpretation': 'Early-game expected final score under the frozen stronger continuation policy.'})
    return {'training_positions': len(train), 'validation_positions': len(held), 'features': len(names),
            'training_cases': len({rows[i]['case'] for i in train}), 'validation_cases': len({rows[i]['case'] for i in held}),
            'parent_validation_rmse': float(np.sqrt(np.average(y[held] ** 2, weights=weights[held]))),
            'learned_validation_rmse': rmse, 'penalty': penalty,
            'candidates': [{'penalty': p, 'rmse': e} for e, p, _ in candidates]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--paired-study', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--dev-deals', type=int, default=8)
    parser.add_argument('--test-deals', type=int, default=24)
    args = parser.parse_args(); output = args.output.resolve(); study = args.paired_study.resolve()
    output.mkdir(parents=True, exist_ok=False); source = output / 'source-baseline'; freeze(source)
    status = {'pid': os.getpid(), 'started': time.time(), 'source': str(source), 'paired_study': str(study)}
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
        keep = getattr(local, 'worker', None)
        for w in list(workers):
            if w is not keep: w.close(); workers.remove(w)
    try:
        plan = json.loads((study / 'plan.json').read_text())
        samples = [json.loads(line) for line in (study / 'samples.jsonl').read_text().splitlines()]
        assert len(samples) == len(plan['training_case_indices']) + len(plan['validation_case_indices'])
        atomic(output / 'plan.json', {'source_cases': str(study / 'plan.json'),
            'development_seeds': [9900000, 9900000 + args.dev_deals - 1],
            'test_random_seeds': [9910000, 9910000 + args.test_deals - 1],
            'test_draw_seeds': [9920000, 9920000 + args.test_deals - 1],
            'scope': 'Only ordinary work-phase choices through round 10 use the refit; later play and mandatory choices retain parent',
            'selection': 'Highest development mean score margin; final tests never select or retune models'})
        files = [(r['case'], 'source', r['source']['recording']) for r in samples]
        files += [(r['case'], side, r[side]['result']['recording']) for r in samples if not r.get('skipped') for side in ['a', 'b']]
        update('extracting', completed=0, recordings=len(files)); extracts = []
        def extract(job):
            case, side, path = job
            return worker().request(op='extract', case=case, recording=path,
                       output=str(output / 'positions' / f'{case}-{side}.jsonl'))
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(extract, job) for job in files]
            for i, future in enumerate(as_completed(futures), 1):
                extracts.append(future.result())
                if i % 32 == 0 or i == len(files): update('extracting', completed=i, recordings=len(files))
        retire()
        rows = [json.loads(line) for file in sorted(extracts, key=lambda r: r['output'])
                for line in Path(file['output']).read_text().splitlines()]
        schema = worker().request(op='schema'); update('fitting', rows=len(rows))
        diagnostics = fit(rows, set(plan['training_case_indices']), schema, output)
        report = {'fit': diagnostics, 'schema': {k: v for k, v in schema.items() if k != 'parent_weights'},
                  'source_recordings': len(files), 'scope': 'Early work decisions only; deployed stronger parent is opponent in all arms'}
        atomic(output / 'report.json', report); print(json.dumps({'fit': diagnostics}), flush=True)
        variants = {'parent': (None, 1.), 'wide-parent': (None, 3.),
                    'half-narrow': ('half', 1.), 'full-narrow': ('full', 1.),
                    'half-wide': ('half', 3.), 'full-wide': ('full', 3.)}
        def tournament(stage, policies, seed0, mode, deals):
            jobs = [(name, seed, seat) for name in policies for seed in range(seed0, seed0 + deals) for seat in [0, 1]]
            results = {name: [] for name in policies}; update(stage, completed=0, games=len(jobs))
            def play(job):
                name, seed, seat = job; model, margin = policies[name]
                row = worker().request(op='game', seed=seed, focal=seat, mode=mode, margin=margin,
                    model=str(output / f'{model}.json') if model else None,
                    output=str(output / 'recordings' / f'{stage}-{name}-{seed}-{seat}.edn'))
                return name, row
            with (output / 'games.jsonl').open('a') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
                futures = [pool.submit(play, job) for job in jobs]
                for i, future in enumerate(as_completed(futures), 1):
                    name, row = future.result(); results[name].append(row)
                    log.write(json.dumps({'stage': stage, 'variant': name, **row}) + '\n'); log.flush()
                    if i % 8 == 0 or i == len(jobs): update(stage, completed=i, games=len(jobs))
            retire()
            return {name: {'summary': summary(games), 'behavior': behavior(games), 'paired_vs_parent': paired(results['parent'], games)}
                    for name, games in results.items()}
        report['development'] = tournament('development', variants, 9900000, 'random', args.dev_deals)
        selected = max(report['development'], key=lambda k: report['development'][k]['summary']['mean_margin'])
        report['selected'] = selected; atomic(output / 'report.json', report)
        if selected != 'parent':
            policies = {k: variants[k] for k in ['parent', selected]}
            report['test_random'] = tournament('test-random', policies, 9910000, 'random', args.test_deals)
            atomic(output / 'report.json', report)
            report['test_draw'] = tournament('test-draw', policies, 9920000, 'draw', args.test_deals)
            report['promotion_supported'] = all(min(report[mode][selected]['paired_vs_parent'][metric][0]
                for metric in ['score_gain_95_interval', 'margin_gain_95_interval']) > 0 for mode in ['test_random', 'test_draw'])
        else: report['promotion_supported'] = False
        atomic(output / 'report.json', report)
        update('complete', selected=selected, promotion_supported=report['promotion_supported'])
    except BaseException as exc:
        update('failed', error=str(exc)); traceback.print_exc(); raise
    finally:
        for w in workers: w.close()


if __name__ == '__main__': main()
