"""Fit card-aware afterstate values; compare ablations on identical fresh deals.

Targets are observed final scores under the recorded policy, not causal action
values. Positions are split by whole game. Fresh gameplay is the strength test.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import selectors
import subprocess
import threading
import time
import traceback
import numpy as np
import torch
from overnight import freeze, atomic
from card_expectations import summary, paired

ROOT = Path(__file__).resolve().parents[1]
KINDS = ('board', 'cards', 'combos')


class Worker:
    def __init__(self, root):
        self.process = subprocess.Popen(
            ['clojure', '-J-Xmx1g', '-J-XX:ActiveProcessorCount=2', '-M', '-m', 'agricola.afterstate-value'],
            cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)

    def request(self, **message):
        self.process.stdin.write(json.dumps(message) + '\n')
        self.process.stdin.flush()
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout, selectors.EVENT_READ)
            if not selector.select(300):
                raise TimeoutError('Afterstate worker exceeded five minutes')
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError('Afterstate worker exited')
        result = json.loads(line)
        if isinstance(result, dict) and 'error' in result:
            raise RuntimeError(result['error'])
        return result

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=10)


def feature_names(rows, train):
    return sorted({k for i in train for k in rows[i]['features']})


def fit_models(rows, train, held, output, schema, device):
    torch.set_num_threads(2)
    names = feature_names(rows, train)
    index = {k: i for i, k in enumerate(names)}
    x = np.zeros((len(rows), len(names)), np.float32)
    for i, row in enumerate(rows):
        for name, value in row['features'].items():
            if name in index:
                x[i, index[name]] = value
    y = np.array([r['target'] - r['utility'] for r in rows], np.float32)
    counts = {}
    for i in train:
        counts[rows[i]['seed']] = counts.get(rows[i]['seed'], 0) + 1
    # Each independent deal has equal total fitting weight, regardless of length.
    w = np.array([1 / counts[rows[i]['seed']] for i in train], np.float32)
    scales = np.array([.25 if n.startswith('pair/') else .5 if n.startswith('card/') else 1 for n in names], np.float32)
    results = {}
    for kind in KINDS:
        cols = [i for i, n in enumerate(names)
                if n.startswith('base/') or kind == 'combos' or (kind == 'cards' and n.startswith('card/'))]
        matrix = torch.tensor(x[train][:, cols] * scales[cols], device=device)
        target = torch.tensor(y[train], device=device)
        weight = torch.tensor(w, device=device)
        gram = matrix.T @ (matrix * weight[:, None])
        rhs = matrix.T @ (target * weight)
        identity = torch.eye(len(cols), device=device)
        identity[cols.index(index['base/bias']), cols.index(index['base/bias'])] = 0
        candidates = []
        for penalty in [.1, 1., 10.]:
            coef = torch.linalg.solve(gram + penalty * identity, rhs).cpu().numpy() * scales[cols]
            residual = x[held][:, cols] @ coef - y[held]
            # Validation also weights complete games equally.
            grouped = {}
            for i, error in zip(held, residual):
                grouped.setdefault(rows[i]['seed'], []).append(float(error * error))
            rmse = float(np.sqrt(np.mean([np.mean(v) for v in grouped.values()])))
            candidates.append((rmse, penalty, coef))
        rmse, penalty, coef = min(candidates, key=lambda v: v[0])
        weights = {names[i]: float(v) for i, v in zip(cols, coef)}
        # Export absolute expected score. Fitting learns a residual over utility.
        weights['base/utility'] = weights.get('base/utility', 0.) + 40.
        model = {**schema, 'kind': kind, 'weights': weights, 'intercept': 0.,
                 'penalty': penalty, 'validation_rmse': rmse,
                 'interpretation': 'Expected score under recorded continuation policy; not causal card values.'}
        atomic(output / f'{kind}.json', model)
        results[kind] = {'validation_rmse': rmse, 'penalty': penalty, 'features': len(cols),
                         'candidates': [{'penalty': p, 'rmse': e} for e, p, _ in candidates]}
        print(json.dumps({'fit': kind, **results[kind]}), flush=True)
        del matrix, target, weight, gram, rhs, identity
        if device == 'cuda':
            torch.cuda.empty_cache()
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--study', type=Path, default=ROOT / 'data/muzero/overnight-20261026/study')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--dev-deals', type=int, default=8)
    parser.add_argument('--test-deals', type=int, default=24)
    parser.add_argument('--device', choices=['cuda', 'cpu'], default='cuda')
    parser.add_argument('--limit', type=int, help='Smoke run only: first N source deals')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    source = output / 'source-baseline'
    freeze(source)
    status = {'pid': os.getpid(), 'started': time.time(), 'status': 'starting', 'source': str(source)}
    def update(stage, **extra):
        status.update(status=stage, **extra)
        atomic(output / 'status.json', status)
        print(json.dumps({'status': stage, **extra}), flush=True)
    workers = []
    local = threading.local()
    lock = threading.Lock()
    def worker():
        if not hasattr(local, 'worker'):
            local.worker = Worker(source)
            with lock:
                workers.append(local.worker)
        return local.worker
    def retire_pool_workers():
        main = getattr(local, 'worker', None)
        for w in list(workers):
            if w is not main:
                w.close()
                workers.remove(w)
    try:
        study_rows = sorted([json.loads(line) for line in (args.study / 'results.jsonl').read_text().splitlines()
                             if json.loads(line)['stage'] == 'random'], key=lambda r: r['seed'])
        split = json.loads((args.study / 'card-values.json').read_text())
        train_seeds = {study_rows[i]['seed'] for i in split['train_indices']}
        held_seeds = {study_rows[i]['seed'] for i in split['heldout_indices']}
        assert not train_seeds & held_seeds
        if args.limit:
            study_rows = study_rows[:args.limit]
        update('extracting', source_games=len(study_rows), completed=0)
        extracts = []
        def extract(row):
            return worker().request(op='extract', recording=row['recording'],
                                    output=str(output / 'positions' / f"{row['seed']}.jsonl"), stride=2)
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(extract, r) for r in study_rows]
            for future in as_completed(futures):
                extracts.append(future.result())
                if len(extracts) % 32 == 0:
                    update('extracting', completed=len(extracts))
        retire_pool_workers()
        rows = [json.loads(line) for r in sorted(extracts, key=lambda r: r['seed'])
                for line in Path(r['output']).read_text().splitlines()]
        train = np.array([i for i, r in enumerate(rows) if r['seed'] in train_seeds])
        held = np.array([i for i, r in enumerate(rows) if r['seed'] in held_seeds])
        assert len(train) and len(held) and len(train) + len(held) == len(rows)
        schema = worker().request(op='schema')
        report = {'schema': schema, 'training_seeds': sorted({rows[i]['seed'] for i in train}),
                  'validation_seeds': sorted({rows[i]['seed'] for i in held}), 'rows': len(rows),
                  'replayed_source_games': len(extracts), 'source_moves': sum(r['moves'] for r in extracts),
                  'smoke_only': bool(args.limit), 'selection': 'Highest development mean score margin; final test is fresh.'}
        update('fitting', rows=len(rows), completed=len(extracts))
        report['fit'] = fit_models(rows, train, held, output, schema, args.device)
        # Confirm JSON export and JVM inference agree with Python on real positions.
        for kind in KINDS:
            model = json.loads((output / f'{kind}.json').read_text())
            for row in rows[::max(1, len(rows) // 4)][:4]:
                python = sum(v * model['weights'].get(k, 0.) for k, v in row['features'].items())
                jvm = worker().request(op='predict', model=str(output / f'{kind}.json'), features=row['features'])
                assert abs(python - jvm) < 1e-7, (python, jvm)
        report['inference_parity_checks'] = 12
        atomic(output / 'report.json', report)
        def tournament(stage, variants, first_seed, deals):
            jobs = [(name, kind, blend, seed, seat) for name, (kind, blend) in variants.items()
                    for seed in range(first_seed, first_seed + deals) for seat in [0, 1]]
            results = {name: [] for name in variants}
            update(stage, completed=0, games=len(jobs))
            def play(job):
                name, kind, blend, seed, seat = job
                result = worker().request(op='game', seed=seed, focal=seat,
                    model=str(output / f'{kind}.json') if kind else None, margin=1., blend=blend,
                    output=str(output / 'recordings' / f'{stage}-{name}-{seed}-{seat}.edn'))
                return name, result
            with (output / 'games.jsonl').open('a') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
                futures = [pool.submit(play, job) for job in jobs]
                for i, future in enumerate(as_completed(futures), 1):
                    name, result = future.result()
                    results[name].append(result)
                    log.write(json.dumps({'stage': stage, 'variant': name, **result}) + '\n'); log.flush()
                    if i % 8 == 0 or i == len(jobs):
                        update(stage, completed=i, games=len(jobs))
            retire_pool_workers()
            return {name: {'summary': summary(games),
                            'paired_vs_heuristic': paired(results['heuristic'], games)}
                    for name, games in results.items()}
        variants = {'heuristic': (None, 0.)}
        variants.update({f'{kind}-{blend}': (kind, blend) for kind in KINDS for blend in [.25, .75]})
        report['development'] = tournament('development', variants, 9200000, args.dev_deals)
        selected = max(report['development'], key=lambda n: report['development'][n]['summary']['mean_margin'])
        report['selected'] = selected
        atomic(output / 'report.json', report)
        if selected != 'heuristic':
            report['test'] = tournament('test', {name: variants[name] for name in ['heuristic', selected]}, 9300000, args.test_deals)
        else:
            report['test'] = None
            report['conclusion'] = 'No candidate beat the heuristic in development; no promotion.'
        atomic(output / 'report.json', report)
        update('complete', selected=selected, report=str(output / 'report.json'))
    except BaseException as exc:
        update('failed', error=str(exc))
        traceback.print_exc()
        raise
    finally:
        for w in workers:
            w.close()


if __name__ == '__main__':
    main()
