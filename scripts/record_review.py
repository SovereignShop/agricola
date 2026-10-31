#!/usr/bin/env python3
"""Record a reproducible review corpus using isolated native workers.

Example: python3 scripts/record_review.py --output data/reviews/run --per-group 40
Expose output with a symlink under resources/public/recordings/ to use index.html.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def atomic_json(path, value):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(value, indent=2))
    temp.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--per-group', type=int, default=40)
    parser.add_argument('--workers', type=int, default=8)
    parser.add_argument('--seed', type=int, default=2026103100)
    args = parser.parse_args()
    if args.per_group < 1 or args.per_group > 1000 or not 1 <= args.workers <= 8:
        parser.error('Use 1–1000 games per group and 1–8 workers.')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(ROOT/'scripts/review_index.html', output/'index.html')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    plan = {'commit': commit, 'seed': args.seed, 'per_group': args.per_group,
            'target': args.per_group*8, 'started': datetime.now(timezone.utc).isoformat(),
            'policy': 'Deployed bots; every fifth game has one random-policy seat.',
            'groups': [{'players': p, 'mode': m, 'seed': args.seed+1000*i}
                       for i, (p, m) in enumerate((p, m) for p in range(2, 6) for m in ['random', 'draw'])]}
    atomic_json(output/'plan.json', plan)
    lock = threading.Lock()
    rows, failures = [], []
    started = time.monotonic()

    def publish(done=False):
        atomic_json(output/'manifest.json', dict(plan, games=sorted(rows, key=lambda r: r['seed']),
                    completed=len(rows), elapsed_seconds=time.monotonic()-started,
                    finished=done, worker_failures=failures))

    publish()

    def run(group):
        label = f"{group['players']}p-{group['mode']}"
        command = ['clojure', '-J-Xmx1536m', '-J-XX:ActiveProcessorCount=2', '-M',
                   'scripts/record_review_worker.clj', str(output), str(group['players']),
                   group['mode'], str(args.per_group), str(group['seed'])]
        with (output/f'{label}.stderr.log').open('w') as stderr, (output/f'{label}.jsonl').open('w') as log:
            worker = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=stderr, text=True)
            try:
                for line in worker.stdout:
                    log.write(line); log.flush()
                    row = json.loads(line)
                    with lock:
                        rows.append(row)
                        publish()
                        print(f"{len(rows)}/{plan['target']} {label} seed={row['seed']} {row['status']} scores={row.get('scores')} ({row['seconds']:.1f}s)", flush=True)
                if worker.wait() != 0:
                    raise RuntimeError(f'{label} exited {worker.returncode}; see {label}.stderr.log')
            finally:
                if worker.poll() is None:
                    worker.terminate(); worker.wait(timeout=10)

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for future in as_completed([pool.submit(run, group) for group in plan['groups']]):
            try:
                future.result()
            except Exception as error:
                with lock:
                    failures.append(str(error)); publish()
                print(str(error), flush=True)
    publish(done=True)
    if failures or len(rows) != plan['target']:
        raise SystemExit('Incomplete recording batch; retained all completed games and worker logs.')
    print(f"Finished {len(rows)} recordings in {time.monotonic()-started:.1f}s: {output/'index.html'}", flush=True)


if __name__ == '__main__':
    main()
