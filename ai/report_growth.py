"""Readable results and paired behavioral diagnostics for the growth study."""
import argparse
import html
import json
from pathlib import Path
import numpy as np


def behavior_differences(parent, child):
    key = lambda r: (r['seed'], r['model_seat'])
    parents = {key(r): r for r in parent}
    assert set(parents) == {key(r) for r in child}
    grouped = {}
    for row in child:
        seed, seat = key(row); old = parents[(seed, seat)]
        grouped.setdefault(seed, []).append([
            row['first_growth'][seat] - old['first_growth'][seat],
            row['begging'][seat] - old['begging'][seat],
            row['families'][seat] - old['families'][seat]])
    units = np.array([np.mean(values, axis=0) for _, values in sorted(grouped.items())])
    rng = np.random.default_rng(950221)
    bootstrap = units[rng.integers(0, len(units), size=(10000, len(units)))].mean(1)
    return {name: {'mean_change': float(units[:, i].mean()),
                   'interval_95': np.quantile(bootstrap[:, i], [.025, .975]).tolist()}
            for i, name in enumerate(['first_growth_round', 'begging', 'final_family'])}


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('experiment', type=Path)
    args = parser.parse_args(); p = args.experiment
    r = json.loads((p / 'report.json').read_text())
    status = json.loads((p / 'status.json').read_text())
    rows = [json.loads(line) for line in (p / 'games.jsonl').read_text().splitlines()]
    behavioral = {}
    selected = r.get('selected', 'pending')
    iteration = 'source_recordings' in r
    ranking = 'inference_parity' in r
    if status['status'] == 'complete' and selected != 'parent':
        for stage in ['test-random', 'test-draw']:
            parent = [row for row in rows if row['stage'] == stage and row['variant'] == 'parent']
            child = [row for row in rows if row['stage'] == stage and row['variant'] == selected]
            if parent and child: behavioral[stage] = behavior_differences(parent, child)
        (p / 'behavior-differences.json').write_text(json.dumps(behavioral, indent=2))
    parts = ['<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Eurozone family-growth learning</title>',
             '<style>body{font:17px/1.55 system-ui;max-width:1100px;margin:40px auto;padding:0 22px;background:#faf9f4;color:#21372d}table{border-collapse:collapse;width:100%;margin:20px 0}th,td{padding:8px;border-bottom:1px solid #cbd5cd;text-align:left}pre{white-space:pre-wrap;background:#edf2e9;padding:16px}h1,h2{line-height:1.2}</style>',
             '<h1>Learning the payoff of early family growth</h1>',
             f'<p>Status: {html.escape(status["status"])}. Selected in development: {html.escape(selected)}. Promotion supported: {r.get("promotion_supported", False)}.</p>',
             ('<p><strong>Smoke test only: these small samples check the pipeline and cannot establish playing strength.</strong></p>' if r.get('smoke_only') else ''),
             ('<p>This ranker learns final score-margin changes from one alternative action followed by the frozen stronger parent. Each branch starts at the same full position as its recorded baseline. Up to four different food, preparation, room and growth decisions are sampled per source deal. Linear and nonlinear corrections are selected by held-out decision regret, then complete development games select a policy. No artificial growth reward is added.</p>' if ranking else
              '<p>This refit uses early-game positions from the paired rollout study. It learns expected final own score under the stronger continuation policy, with the previous model as a regularized prior. Only normal work choices through round 10 use the refit; other choices retain the parent.</p>' if iteration else
              '<p>Two legal alternatives start from the same position, then finish under the frozen, deployed card-aware parent policy. Labels are final own-score differences. There is no artificial reward for growing. Growth, rooms and building-resource preparation are sampled separately; the moves include their real costs and attached card effects.</p>'),
             (f'<p>{r["source_recordings"]} verified source recordings, grouped by original source deal. Train/validation partitions stay fixed across all branches.</p>' if iteration else
              f'<p>{r["source_games"]} source games and {r["paired_continuations"]} completed continuations from recorded intermediate positions. Both branches belong to the same independent train/validation partition.</p>'),
             '<h2>Held-out prediction</h2><pre>' + html.escape(json.dumps(r['fit'], indent=2)) + '</pre>']
    for stage in ['development', 'development_random', 'development_draw', 'test_random', 'test_draw']:
        if stage not in r: continue
        parts.append(f'<h2>{html.escape(stage.replace("_", " "))}</h2><table><tr><th>Policy</th><th>Mean score</th><th>Median / best</th><th>Wins</th><th>First growth</th><th>Begging</th><th>Score gain (95% interval)</th></tr>')
        for name, result in r[stage].items():
            s = result['summary']; b = result['behavior']; g = result['paired_vs_parent']
            lo, hi = g['score_gain_95_interval']
            parts.append(f'<tr><td>{html.escape(name)}</td><td>{s["mean_score"]:.2f}</td><td>{s["median_score"]:g} / {s["max_score"]}</td><td>{s["wins"]}/{s["games"]}</td><td>{b["mean_first_growth_round"]:.2f}</td><td>{s["mean_begging"]:.2f}</td><td>{g["score_gain"]:+.2f} [{lo:.2f}, {hi:.2f}]</td></tr>')
        parts.append('</table>')
    parts += ['<p>Tests use the CURRENT stronger bot as opponent in both arms. First growth means the round the family first exceeds two; no growth is counted as round 15. Score intervals and behavioral intervals resample independent deals, grouping both seats.</p>',
              '<h2>Changes in behavior on independent deals</h2><pre>' + html.escape(json.dumps(behavioral, indent=2)) + '</pre>',
              '<p>The wider-filter-only control separates changing the candidate pool from learning a correction. Final tests do not select or retune policies. The live bot changes only after independent gains and browser validation. Rules stay frozen during this study; existing card-timing issues remain tracked separately.</p>',
              '<p><a href="report.json">Machine-readable report</a> · <a href="plan.json">Experiment plan</a> · <a href="games.jsonl">Recorded game index</a></p></html>']
    (p / 'report.html').write_text('\n'.join(parts))


if __name__ == '__main__': main()
