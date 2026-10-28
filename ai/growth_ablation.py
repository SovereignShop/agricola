"""For a selected wide learned policy, isolate learning from the wider filter.
No test results are used to select or retune the policy.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
from pathlib import Path
import threading
from growth_learning import Worker, behavior
from card_expectations import paired, summary
from overnight import atomic


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--experiment', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=6)
    args = parser.parse_args(); experiment = args.experiment.resolve()
    assert json.loads((experiment / 'status.json').read_text())['status'] == 'complete'
    report = json.loads((experiment / 'report.json').read_text())
    assert report['selected'].startswith('learned-wide-') or report['selected'] in ['half-wide', 'full-wide']
    plan = json.loads((experiment / 'ablation-plan.json').read_text())
    output = experiment / 'filter-ablation'; output.mkdir(exist_ok=False)
    (output / 'runner.py').write_bytes(Path(__file__).read_bytes())
    rows = [json.loads(line) for line in (experiment / 'games.jsonl').read_text().splitlines()]
    selected = {stage: [r for r in rows if r['stage'] == stage and r['variant'] == report['selected']]
                for stage in ['test-random', 'test-draw']}
    jobs = [(stage, row) for stage, games in selected.items() for row in games]
    results = {stage: [] for stage in selected}; workers = []; local = threading.local(); lock = threading.Lock()
    def play(job):
        stage, row = job
        if not hasattr(local, 'worker'):
            local.worker = Worker(experiment / 'source-baseline')
            with lock: workers.append(local.worker)
        return stage, local.worker.request(op='game', seed=row['seed'], focal=row['model_seat'],
            mode='draw' if stage == 'test-draw' else 'random', margin=3., strength=0., model=None,
            output=str(output / 'recordings' / f"{stage}-{row['seed']}-{row['model_seat']}.edn"))
    metadata = {'status': 'running', 'selected': report['selected'], 'plan': plan, 'games': len(jobs)}
    atomic(output / 'status.json', metadata)
    try:
        with (output / 'games.jsonl').open('w') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(play, j) for j in jobs]
            for i, future in enumerate(as_completed(futures), 1):
                stage, row = future.result(); results[stage].append(row)
                log.write(json.dumps({'stage': stage, **row}) + '\n'); log.flush()
                metadata.update(completed=i); atomic(output / 'status.json', metadata)
                if i % 8 == 0: print(json.dumps(metadata), flush=True)
        result = {stage: {'wide_parent': summary(games), 'behavior': behavior(games),
                           'learning_vs_same_filter': paired(games, selected[stage])}
                  for stage, games in results.items()}
        atomic(output / 'report.json', result)
        metadata.update(status='complete'); atomic(output / 'status.json', metadata)
    except BaseException as exc:
        metadata.update(status='failed', error=str(exc)); atomic(output / 'status.json', metadata)
        raise
    finally:
        for worker in workers: worker.close()


if __name__ == '__main__': main()
