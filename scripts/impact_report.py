#!/usr/bin/env python3
"""Summarize measured zero-benefit cards; retain per-player examples for inspection."""
import argparse
from collections import Counter, defaultdict
from html import escape
import json
import time
from pathlib import Path


def summarize(manifest):
    cards = {}
    reasons = Counter()
    for game in manifest['games']:
        if game['status'] != 'complete' or not game.get('verified'):
            continue
        for row in game.get('card-impact', []):
            card = cards.setdefault(row['id'], dict(id=row['id'], name=row['name'], played=0, zero=0, reasons=Counter(), examples=[]))
            card['played'] += 1
            if row['zero-impact']:
                card['zero'] += 1
                card['reasons'][row['reason']] += 1
                reasons[row['reason']] += 1
                if len(card['examples']) < 8:
                    card['examples'].append(dict(seed=game['seed'], file=game['file'], player=row['player'],
                                                round=row['played-round'], move=row['first-move']+1,
                                                reason=row['reason'], effects=row['effects'], declined=row['declined']))
    ranked = sorted(cards.values(), key=lambda c: (-c['zero'], -c['played'], c['name']))
    return dict(recorded=manifest['completed'], target=manifest['target'], finished=manifest['finished'],
                complete=sum(g['status']=='complete' for g in manifest['games']),
                failures=[dict(seed=g['seed'], error=g.get('error'), status=g['status']) for g in manifest['games'] if g['status']!='complete'],
                measured_plays=sum(c['played'] for c in ranked), zero_benefit=sum(c['zero'] for c in ranked),
                reasons=dict(reasons), cards=ranked)


def publish(directory):
    manifest=json.loads((directory/'manifest.json').read_text())
    report=summarize(manifest)
    (directory/'impact-report.json').write_text(json.dumps(report,indent=2)+'\n')
    rows=[]
    for c in report['cards']:
        if not c['zero']:continue
        examples='; '.join(f"{x['seed']} P{x['player']+1}, round {x['round']}, move {x['move']}" for x in c['examples'][:3])
        rows.append('<tr><td>'+escape(c['name'])+'</td><td>'+str(c['zero'])+' / '+str(c['played'])+'</td><td>'+escape(', '.join(f'{k}: {v}' for k,v in c['reasons'].items()))+'</td><td>'+escape(examples)+'</td></tr>')
    html='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Card impact audit</title>
<style>body{font:16px/1.5 system-ui;max-width:1300px;margin:auto;padding:24px;background:#f5efdf;color:#352e24}td,th{padding:10px;text-align:left;border-bottom:1px solid #c7baa1}table{width:100%;border-collapse:collapse}a{color:#375c42}</style>
<a href="index.html">Browse games and filter cards</a><h1>Card impact audit</h1>'''
    html+=f"<p>{report['recorded']} / {report['target']} recorded; {report['zero_benefit']} of {report['measured_plays']} played card/player instances have no measured benefit.</p>"
    html+='<p>Zero measured benefit does not establish a rule bug. Passive benefits and final scoring remain outside this tracker. Failed and unverified games are excluded from card statistics.</p>'
    findings_path=directory/'impact-investigation.json'
    if findings_path.exists():
        findings=json.loads(findings_path.read_text())
        html+='<h2>Investigated cases</h2><p>'+escape(findings['scope'])+'</p><ul>'
        for case in findings['cases']:
            html+='<li><b>'+escape(case['card'])+'</b>: '+escape(case['finding'])+' ('+escape(str(case['seed']))+', player '+str(case['player'])+')</li>'
        html+='</ul>'
    html+='<table><thead><tr><th>Card</th><th>Zero / played</th><th>Classification</th><th>Example positions</th></tr></thead><tbody>'+''.join(rows)+'</tbody></table><script>if(new URLSearchParams(location.search).get("embedded")==="1")document.querySelector("a").search="?embedded=1";</script></html>'
    (directory/'impact-report.html').write_text(html)
    print(json.dumps({k:v for k,v in report.items() if k!='cards'},indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('directory',type=Path)
    parser.add_argument('--watch',action='store_true',help='Refresh once a minute until this batch finishes.')
    args=parser.parse_args()
    while True:
        publish(args.directory)
        if not args.watch or json.loads((args.directory/'manifest.json').read_text())['finished']:break
        time.sleep(60)
