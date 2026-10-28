"""Independent final-test ablation at the development-selected blend.

Compare all three feature sets without selecting a new policy on test results.
Reuse completed test arms; generate and verify only missing arms.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import threading
from afterstate_value import Worker, KINDS
from card_expectations import paired, summary
from overnight import atomic


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--experiment', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()
    experiment = args.experiment.resolve()
    report = json.loads((experiment / 'report.json').read_text())
    assert json.loads((experiment / 'status.json').read_text())['status'] == 'complete'
    assert report['selected'] != 'heuristic', 'No model selected in development'
    plan = json.loads((experiment / 'ablation-plan.json').read_text())
    assert plan['blend_selection'] == 'development-selected blend'
    blend = float(report['selected'].split('-')[-1])
    rows = [json.loads(line) for line in (experiment / 'games.jsonl').read_text().splitlines()]
    rows = [r for r in rows if r['stage'] == 'test']
    baseline = [r for r in rows if r['variant'] == 'heuristic']
    assert {(r['seed'], r['model_seat']) for r in baseline} == {
        (seed, seat) for seed in range(plan['test_seeds'][0], plan['test_seeds'][1] + 1) for seat in [0, 1]}
    selected = report['selected'].split('-')[0]
    results = {kind: [r for r in rows if r['variant'] == report['selected']] if kind == selected else [] for kind in KINDS}
    output = experiment / 'test-ablation'
    output.mkdir(exist_ok=False)
    source = Path(__file__).read_bytes()
    (output / 'afterstate_ablation.py').write_bytes(source)
    metadata = {'status': 'running', 'blend': blend, 'selected_in_development': selected,
                'runner_sha256': hashlib.sha256(source).hexdigest(), 'plan': plan}
    atomic(output / 'status.json', metadata)
    workers = []
    local = threading.local()
    lock = threading.Lock()
    def play(job):
        kind, row = job
        if not hasattr(local, 'worker'):
            local.worker = Worker(experiment / 'source-baseline')
            with lock:
                workers.append(local.worker)
        return kind, local.worker.request(op='game', seed=row['seed'], focal=row['model_seat'],
            model=str(experiment / f'{kind}.json'), margin=1., blend=blend,
            output=str(output / 'recordings' / f"{kind}-{row['seed']}-{row['model_seat']}.edn"))
    try:
        jobs = [(kind, row) for kind in KINDS if kind != selected for row in baseline]
        with (output / 'games.jsonl').open('w') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(play, job) for job in jobs]
            for i, future in enumerate(as_completed(futures), 1):
                kind, result = future.result()
                results[kind].append(result)
                log.write(json.dumps({'kind': kind, **result}) + '\n'); log.flush()
                metadata.update(completed=i, games=len(jobs))
                atomic(output / 'status.json', metadata)
                if i % 8 == 0:
                    print(json.dumps(metadata), flush=True)
        result = {'blend': blend, 'selected_in_development': selected,
                  'heuristic': summary(baseline),
                  'models': {kind: {'summary': summary(games), 'vs_heuristic': paired(baseline, games)}
                             for kind, games in results.items()},
                  'cards_vs_board': paired(results['board'], results['cards']),
                  'combos_vs_cards': paired(results['cards'], results['combos']),
                  'interpretation': 'Fixed independent ablations; no policy selection or tuning on this test.'}
        atomic(output / 'report.json', result)
        metadata.update(status='complete')
        atomic(output / 'status.json', metadata)
    except BaseException as exc:
        metadata.update(status='failed', error=str(exc))
        atomic(output / 'status.json', metadata)
        raise
    finally:
        for worker in workers:
            worker.close()


if __name__ == '__main__':
    main()
