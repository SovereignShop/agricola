"""Learn action preferences from matched native continuations and test fresh deals.

Whole source deals stay in their original split. Final tests never select models.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
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
from overnight import freeze, atomic
from card_expectations import summary, paired
from growth_learning import behavior


class Worker(BaseWorker):
    def __init__(self, root):
        self.process = subprocess.Popen(
            ['clojure', '-J-Xmx1g', '-J-XX:ActiveProcessorCount=2', '-M', '-m', 'agricola.action-rank'],
            cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)


def design(rows, training_cases):
    train = np.array([i for i, r in enumerate(rows) if r['case'] in training_cases])
    held = np.array([i for i, r in enumerate(rows) if r['case'] not in training_cases])
    names = sorted({k for i in train for side in ['a', 'b'] for k in rows[i][side]['features']})
    x = np.array([[[r[side]['features'].get(k, 0.) for k in names] for side in ['a', 'b']]
                  for r in rows], np.float32)
    parent = np.array([r['a']['parent-score'] - r['b']['parent-score'] for r in rows], np.float32)
    y = np.array([r['margin_difference'] for r in rows], np.float32)
    counts = {case: sum(r['case'] == case for r in rows) for case in {r['case'] for r in rows}}
    weights = np.array([1. / counts[r['case']] for r in rows], np.float32)
    return names, x, parent, y, weights, train, held


class Ranker(torch.nn.Module):
    def __init__(self, inputs, width):
        super().__init__()
        self.linear = torch.nn.Linear(inputs, 1, bias=False)
        torch.nn.init.zeros_(self.linear.weight)
        self.hidden = torch.nn.Linear(inputs, width) if width else None
        self.out = torch.nn.Linear(width, 1, bias=False) if width else None
        if width: torch.nn.init.zeros_(self.out.weight)

    def forward(self, x):
        result = self.linear(x).squeeze(-1)
        if self.hidden is not None: result = result + self.out(torch.tanh(self.hidden(x))).squeeze(-1)
        return result

    def portable(self):
        return {'linear': self.linear.weight.detach().cpu().numpy()[0].tolist(),
                'hidden': self.hidden.weight.detach().cpu().tolist() if self.hidden else [],
                'bias': self.hidden.bias.detach().cpu().tolist() if self.hidden else [],
                'out': self.out.weight.detach().cpu().numpy()[0].tolist() if self.out else []}


def portable(member, x):
    out = x @ np.array(member['linear'])
    if member['hidden']:
        out = out + np.tanh(x @ np.array(member['hidden']).T + member['bias']) @ np.array(member['out'])
    return out


def metrics(pred, y, w):
    regret = np.maximum(y, 0) - (pred > 0) * y
    return {'regret': float(np.average(regret, weights=w)),
            'rmse': float(np.sqrt(np.average((pred-y)**2, weights=w))),
            'accuracy': float(np.average((pred > 0) == (y > 0), weights=w))}


def fit(rows, training_cases, schema, output, smoke=False):
    names, x, parent, y, weights, train, held = design(rows, training_cases)
    assert len(train) >= 3 and len(held) >= 2
    torch.set_num_threads(2)
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    tx, tp, ty, tw = [torch.tensor(v, device=device) for v in (x, parent, y, weights)]
    ti = torch.tensor(train, device=device); hi = torch.tensor(held, device=device)
    candidates = []
    for width in [0, 24]:
        for penalty in [.001, .01, .1]:
            members = []; predictions = []
            for seed in [1031, 2039, 3041]:
                torch.manual_seed(seed)
                model = Ranker(len(names), width).to(device)
                optimizer = torch.optim.Adam(model.parameters(), lr=.01)
                best = None
                for epoch in range(1, (60 if smoke else 600) + 1):
                    model.train(); optimizer.zero_grad()
                    score = tp[ti] + model(tx[ti, 0]) - model(tx[ti, 1])
                    error = torch.nn.functional.smooth_l1_loss(score, ty[ti], beta=4., reduction='none')
                    target = (torch.sign(ty[ti]) + 1.) / 2.
                    error = error + .25 * torch.nn.functional.binary_cross_entropy_with_logits(score / 3., target, reduction='none')
                    loss = (error * tw[ti]).sum() / tw[ti].sum()
                    loss = loss + penalty * sum(p.square().sum() for p in model.parameters())
                    loss.backward(); optimizer.step()
                    if epoch % 20 == 0:
                        model.eval()
                        with torch.no_grad():
                            pred = (tp[hi] + model(tx[hi, 0]) - model(tx[hi, 1])).cpu().numpy()
                        m = metrics(pred, y[held], weights[held]); key = (m['regret'], m['rmse'])
                        if best is None or key < best[0]: best = (key, model.portable(), epoch)
                        if epoch - best[2] >= 160: break
                members.append(best[1])
                predictions.append(parent[held] + portable(best[1], x[held, 0]) - portable(best[1], x[held, 1]))
            m = metrics(np.mean(predictions, axis=0), y[held], weights[held])
            candidate = {'width': width, 'penalty': penalty, 'validation': m, 'members': members}
            candidates.append(candidate)
            print(json.dumps({'fit': {k: v for k, v in candidate.items() if k != 'members'}}), flush=True)
    chosen = min(candidates, key=lambda c: (c['validation']['regret'], c['validation']['rmse']))
    model = {**schema, 'names': names, 'members': chosen['members'],
             'interpretation': 'Residual action preference from paired final score-margin changes; no artificial growth reward.'}
    atomic(output / 'rank.json', model)
    diagnostics = {'training_pairs': len(train), 'validation_pairs': len(held), 'features': len(names),
                   'training_cases': len({rows[i]['case'] for i in train}),
                   'validation_cases': len({rows[i]['case'] for i in held}),
                   'parent_validation': metrics(parent[held], y[held], weights[held]),
                   'selected': {k: v for k, v in chosen.items() if k != 'members'},
                   'candidates': [{k: v for k, v in c.items() if k != 'members'} for c in candidates]}
    return model, diagnostics


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=8)
    parser.add_argument('--dev-deals', type=int, default=8)
    parser.add_argument('--test-deals', type=int, default=24)
    parser.add_argument('--limit', type=int)
    parser.add_argument('--seed', type=int, default=10400000)
    args = parser.parse_args(); output = args.output.resolve(); study = args.study.resolve()
    output.mkdir(parents=True, exist_ok=False); source = output / 'source-baseline'; freeze(source)
    status = {'pid': os.getpid(), 'started': time.time(), 'source': str(source), 'smoke_only': bool(args.limit)}
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
        old_plan = json.loads((study/'plan.json').read_text())
        sources = sorted([json.loads(s) for s in (study/'samples.jsonl').read_text().splitlines()], key=lambda r: r['case'])
        if args.limit: sources = sources[:args.limit]
        plan = {'source_study': str(study), 'training_cases': old_plan['training_case_indices'],
                'validation_cases': old_plan['validation_case_indices'],
                'sampling': 'Up to four different early decision points per deal: food, preparation, room, growth; alternate within 5 utility points',
                'target': 'Final score-margin difference after one intervention and identical frozen-parent continuation',
                'fit': 'Linear and 24-unit tanh residual rankers; penalties .001/.01/.1; three fixed-seed members; held-out deal-weighted regret selects',
                'policy': 'Only ordinary work through round 10; correction bounded to 4 points; preserve parent on other decisions',
                'development_random': [args.seed, args.seed+args.dev_deals-1],
                'development_draw': [args.seed+10000, args.seed+10000+args.dev_deals-1],
                'test_random': [args.seed+100000, args.seed+100000+args.test_deals-1],
                'test_draw': [args.seed+110000, args.seed+110000+args.test_deals-1],
                'selection': 'Highest average development margin across random and draft; include unchanged and wider-filter controls',
                'promotion': 'Both score and margin 95% deal-bootstrap intervals positive against current parent in both modes; no promotion from smoke'}
        atomic(output/'plan.json', plan)
        update('branching', completed=0, source_games=len(sources)); rows = []
        def sample(row):
            return worker().request(op='sample', recording=row['source']['recording'], case=row['case'],
                                    output=str(output/'branches'/str(row['case'])))
        with (output/'samples.jsonl').open('w') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(sample, row) for row in sources]
            for i, future in enumerate(as_completed(futures), 1):
                result = future.result(); log.write(json.dumps(result)+'\n'); log.flush()
                rows.extend({'case': result['case'], **p} for p in result['pairs'])
                if i % 8 == 0 or i == len(sources): update('branching', completed=i, source_games=len(sources), pairs=len(rows))
        retire(); rows.sort(key=lambda r: (r['case'], r['source_move']))
        update('fitting', pairs=len(rows)); schema = worker().request(op='schema')
        model, diagnostics = fit(rows, set(plan['training_cases']), schema, output, bool(args.limit))
        # Verify exported inference in the native runtime before gameplay evaluation.
        for row in rows[::max(1, len(rows)//10)]:
            f = row['a']['features']; x = np.array([f.get(n, 0.) for n in model['names']])
            expected = [float(portable(m, x)) for m in model['members']]
            actual = worker().request(op='predict', model=str(output/'rank.json'), features_json=json.dumps(f))
            np.testing.assert_allclose(actual, expected, atol=1e-5, rtol=1e-5)
        report = {'fit': diagnostics, 'source_games': len(sources), 'paired_continuations': len(rows),
                  'schema': schema, 'smoke_only': bool(args.limit), 'inference_parity': 'passed',
                  'categories': {k: sum(r['category']==k for r in rows) for k in sorted({r['category'] for r in rows})}}
        atomic(output/'report.json', report)
        variants = {'parent': (1., 0.), 'wide-parent': (3., 0.),
                    'rank-narrow-0.5': (1., .5), 'rank-wide-0.5': (3., .5), 'rank-wide-1.0': (3., 1.)}
        atomic(output/'variants.json', variants)
        def tournament(stage, policies, seed0, mode, deals):
            jobs = [(name, seed, seat) for name in policies for seed in range(seed0, seed0+deals) for seat in [0, 1]]
            results = {k: [] for k in policies}; update(stage, completed=0, games=len(jobs))
            def play(job):
                name, seed, seat = job; margin, strength = policies[name]
                return name, worker().request(op='game', seed=seed, focal=seat, mode=mode, margin=margin, strength=strength,
                    model=str(output/'rank.json') if strength else None,
                    output=str(output/'recordings'/f'{stage}-{name}-{seed}-{seat}.edn'))
            with (output/'games.jsonl').open('a') as log, ThreadPoolExecutor(max_workers=args.workers) as pool:
                for i, future in enumerate(as_completed([pool.submit(play, job) for job in jobs]), 1):
                    name, row = future.result(); results[name].append(row)
                    log.write(json.dumps({'stage': stage, 'variant': name, **row})+'\n'); log.flush()
                    if i % 8 == 0 or i == len(jobs): update(stage, completed=i, games=len(jobs))
            retire()
            return {name: {'summary': summary(games), 'behavior': behavior(games),
                           'paired_vs_parent': paired(results['parent'], games),
                           **({'paired_vs_wide_parent': paired(results['wide-parent'], games)}
                              if 'wide-parent' in results else {})} for name, games in results.items()}
        report['development_random'] = tournament('development-random', variants, args.seed, 'random', args.dev_deals)
        atomic(output/'report.json', report)
        report['development_draw'] = tournament('development-draw', variants, args.seed+10000, 'draw', args.dev_deals)
        selected = max(variants, key=lambda k: sum(report[s][k]['summary']['mean_margin'] for s in ['development_random','development_draw']))
        report['selected'] = selected; atomic(output/'report.json', report)
        if selected != 'parent':
            # Include the wider-only control on final deals when widening was selected.
            names = list(dict.fromkeys(['parent', selected] + (['wide-parent'] if 'wide' in selected else [])))
            policies = {k: variants[k] for k in names}
            report['test_random'] = tournament('test-random', policies, args.seed+100000, 'random', args.test_deals)
            atomic(output/'report.json', report)
            report['test_draw'] = tournament('test-draw', policies, args.seed+110000, 'draw', args.test_deals)
            report['promotion_supported'] = not args.limit and all(report[mode][selected]['paired_vs_parent'][metric][0]>0
                for mode in ['test_random','test_draw'] for metric in ['score_gain_95_interval','margin_gain_95_interval'])
        else: report['promotion_supported'] = False
        atomic(output/'report.json', report)
        update('complete', selected=selected, promotion_supported=report['promotion_supported'])
    except BaseException as exc:
        update('failed', error=str(exc)); traceback.print_exc(); raise
    finally:
        for w in workers: w.close()


if __name__ == '__main__': main()
