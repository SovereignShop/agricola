"""Fresh 14+2 draft test: same deployed draft choices, different post-draft play."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import threading
from afterstate_value import Worker
from card_expectations import summary, paired
from overnight import atomic


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--experiment', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--deals', type=int, default=24)
    args = parser.parse_args()
    experiment = args.experiment.resolve()
    report = json.loads((experiment / 'report.json').read_text())
    assert json.loads((experiment / 'status.json').read_text())['status'] == 'complete'
    assert report['selected'] != 'heuristic'
    kind, blend = report['selected'].split('-'); blend = float(blend)
    output = experiment / 'draft-test'; output.mkdir(exist_ok=False)
    for name in ['afterstate_draw.py', 'afterstate_draw.clj']:
        shutil.copy2(Path(__file__).with_name(name), output / name)
    metadata = {'status': 'running', 'mode': '14+2 draft', 'selected': report['selected'],
                'draft_policy': 'Deployed shrunk EV for both sides, identical across arms',
                'source_sha256': {n: hashlib.sha256((output / n).read_bytes()).hexdigest()
                                  for n in ['afterstate_draw.py', 'afterstate_draw.clj']}}
    atomic(output / 'status.json', metadata)
    workers = []; local = threading.local(); lock = threading.Lock()
    def play(job):
        policy, seed, seat = job
        if not hasattr(local, 'worker'):
            local.worker = Worker.__new__(Worker)
            local.worker.process = subprocess.Popen(
                ['clojure', '-J-Xmx1g', '-J-XX:ActiveProcessorCount=2', '-M', str(output / 'afterstate_draw.clj')],
                cwd=experiment / 'source-baseline', stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
            with lock: workers.append(local.worker)
        return policy, local.worker.request(op='game', seed=seed, focal=seat, margin=1., blend=blend,
            model=str(experiment / f'{kind}.json') if policy == 'learned' else None,
            output=str(output / 'recordings' / f'{policy}-{seed}-{seat}.edn'))
    results = {'heuristic': [], 'learned': []}
    try:
        jobs = [(policy, seed, seat) for policy in results for seed in range(9400000, 9400000 + args.deals) for seat in [0, 1]]
        with (output / 'games.jsonl').open('w') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(play, j) for j in jobs]
            for i, future in enumerate(as_completed(futures), 1):
                policy, row = future.result(); results[policy].append(row)
                log.write(json.dumps({'policy': policy, **row}) + '\n'); log.flush()
                metadata.update(completed=i, games=len(jobs)); atomic(output / 'status.json', metadata)
                if i % 8 == 0: print(json.dumps(metadata), flush=True)
        # The last draft move transitions to work. Its state hash proves that
        # starting hands and every other game-state detail match across policies.
        by_deal = {}
        import re
        for games in results.values():
            for row in games:
                lines = Path(row['recording']).read_text().splitlines()
                draft = [line for line in lines if ':type :pick' in line and ':record :move' in line]
                assert len(draft) == 28
                hashes = re.findall(r':hash "([0-9a-f]{64})"', draft[-1])
                assert len(hashes) == 1
                by_deal.setdefault(row['seed'], set()).add(hashes[0])
        assert all(len(hashes) == 1 for hashes in by_deal.values()), 'Draft hands differ across arms'
        result = {'selected': report['selected'], 'mode': '14+2 draft', 'identical_drafts_verified': len(by_deal),
                  'heuristic': summary(results['heuristic']), 'learned': summary(results['learned']),
                  'paired': paired(results['heuristic'], results['learned'])}
        atomic(output / 'report.json', result)
        metadata.update(status='complete'); atomic(output / 'status.json', metadata)
    except BaseException as exc:
        metadata.update(status='failed', error=str(exc)); atomic(output / 'status.json', metadata)
        raise
    finally:
        for worker in workers: worker.close()


if __name__ == '__main__':
    main()
