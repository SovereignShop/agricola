"""Deduplicate and replay the complete recording corpus, then compare openings.
Historical rules are kept separate; controlled card-study treatments never enter
the ordinary-game comparisons. No recording or trained checkpoint is modified.
"""
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
import csv
import html
import json
import os
from pathlib import Path
import selectors
import statistics as stats
import subprocess
import threading
import time

ROOT=Path(__file__).resolve().parents[1]

class Reader:
    def __init__(self,source):
        self.process=subprocess.Popen(['clojure','-J-Xmx768m','-J-XX:ActiveProcessorCount=1','-Sdeps',
              '{:deps {org.clojure/data.json {:mvn/version "2.5.2"}}}','-M',str(ROOT/'ai/analyze_games.clj')],
              cwd=source,stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,bufsize=1)
    def request(self,**message):
        self.process.stdin.write(json.dumps(message)+'\n');self.process.stdin.flush()
        with selectors.DefaultSelector() as s:
            s.register(self.process.stdout,selectors.EVENT_READ)
            if not s.select(180):raise TimeoutError('Recording analysis exceeded three minutes')
        line=self.process.stdout.readline()
        if not line:raise RuntimeError('Recording reader exited')
        return json.loads(line)
    def close(self):
        self.process.stdin.close()
        try:self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:self.process.kill();self.process.wait()

def atomic(path,value):
    tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(value,indent=2));tmp.replace(path)

def policies():
    result={}
    for path in (ROOT/'data').rglob('metrics.jsonl'):
        if any('source-baseline' in p for p in path.parts):continue
        for line in path.open():
            try:row=json.loads(line)
            except json.JSONDecodeError:continue
            if row.get('recording') and row.get('policy'):
                result[str((ROOT/row['recording']).resolve())]=row['policy']
    return result

def summary(rows):
    return {'seats':len(rows),'wins':sum(r['win'] and not r['tie'] for r in rows),
            'draws':sum(r['win'] and r['tie'] for r in rows),
            'win_rate':round(sum(r['win'] and not r['tie'] for r in rows)/len(rows),4) if rows else None,
            'mean_score':round(stats.mean(r['score'] for r in rows),3) if rows else None,
            'median_score':stats.median(r['score'] for r in rows) if rows else None,
            'mean_begging':round(stats.mean(r['begging'] for r in rows),3) if rows else None,
            'mean_first_growth':round(stats.mean(r['growth-rounds'][0] if r['growth-rounds'] else 15 for r in rows),3) if rows else None}

def table(rows,key,predicate):
    return {'feature':key,'yes':summary([r for r in rows if predicate(r)]),'no':summary([r for r in rows if not predicate(r)])}

def report_games(games,current_rules):
    allseats=[]
    for game in games:
        for row in game['seats']:
            allseats.append(dict(row,game=game['id'],path=game['path'],rules=game['rules'],players=game['players'],mode=game['mode'],
                                 study=(game.get('study') or {}).get('kind'),policy=game['resolved_policies'][row['seat']],
                                 copies=game['copies'],partial_start=game['partial-start'] or game['initial-round']>1))
    ordinary=[r for r in allseats if r['study']!='factorial' and not r['partial_start']]
    current=[r for r in ordinary if r['rules']==current_rules and r['players']==2]
    neural=[r for r in current if r['policy']=='muzero']
    def comparisons(rows):
        rows=[r for r in rows if 'pre-harvest-4' in r['snapshots'] and 'start-8' in r['snapshots']]
        snap=lambda r,k:r['snapshots'][k]
        tests=[('Can cover first harvest, including all edible stock',lambda r:snap(r,'pre-harvest-4')['liquid-food']>=snap(r,'pre-harvest-4')['food-required']),
               ('No begging through round 7',lambda r:snap(r,'start-8')['begging']==0),
               ('Kitchen by first harvest',lambda r:snap(r,'pre-harvest-4')['kitchen']),
               ('Productive fields by first harvest',lambda r:snap(r,'pre-harvest-4')['productive-fields']>0),
               ('Spare room at entry to round 5',lambda r:snap(r,'start-5')['rooms']>snap(r,'start-5')['family']),
               ('Third family member by end of round 7',lambda r:snap(r,'start-8')['family']>=3),
               ('Third family member AND no begging through round 7',lambda r:snap(r,'start-8')['family']>=3 and snap(r,'start-8')['begging']==0),
               ('Kitchen by end of round 7',lambda r:snap(r,'start-8')['kitchen']),
               ('At least one fishing/labor/traveling action by round 4',lambda r:snap(r,'pre-harvest-4')['food-space-actions']>=1)]
        return [table(rows,key,pred) for key,pred in tests]
    cohorts={}
    for key in sorted({(r['rules'],r['players'],r['mode'],r['policy'],r['study'] or 'ordinary') for r in allseats}):
        members=[r for r in allseats if (r['rules'],r['players'],r['mode'],r['policy'],r['study'] or 'ordinary')==key]
        cohorts[' | '.join(map(str,key))]=summary(members)
    metadata=policies()
    held=[]
    for row in current:
        for copy in row['copies']:
            if '/overnight-20261026/training/' in copy and Path(copy).name.startswith(('test-best-','test-draft-best-')):
                policy=metadata.get(copy)
                if policy and (policy[row['seat']] if isinstance(policy,list) else policy)=='muzero':held.append(dict(row,path=copy))
    winners=sorted([r for r in neural if r['win'] and not r['tie'] and r['score']>=25 and r['growth-rounds'] and r['growth-rounds'][0]<=8],key=lambda r:r['score'],reverse=True)
    growth_food=[]
    for growth in [True,False]:
        for kitchen in [True,False]:
            group=[r for r in neural if 'start-8' in r['snapshots'] and (r['snapshots']['start-8']['family']>=3)==growth and r['snapshots']['start-8']['kitchen']==kitchen]
            growth_food.append({'third_worker_by_round_7':growth,'cooking_by_round_7':kitchen,**summary(group)})
    by_growth=[]
    for label,pred in [('by round 6',lambda r:r['growth-rounds'] and r['growth-rounds'][0]<=6),
                       ('rounds 7–8',lambda r:r['growth-rounds'] and 7<=r['growth-rounds'][0]<=8),
                       ('rounds 9–11',lambda r:r['growth-rounds'] and 9<=r['growth-rounds'][0]<=11),
                       ('rounds 12–14',lambda r:r['growth-rounds'] and r['growth-rounds'][0]>=12),
                       ('never',lambda r:not r['growth-rounds'])]:
        by_growth.append({'first_growth':label,**summary([r for r in neural if pred(r)])})
    return {'all_seats':len(allseats),'cohorts':cohorts,'current_two_player':summary(current),'neural':summary(neural),
            'latest_heldout':summary(held),'neural_comparisons':comparisons(neural),'heldout_comparisons':comparisons(held),
            'neural_random_comparisons':comparisons([r for r in neural if r['mode']=='random']),
            'neural_draw_comparisons':comparisons([r for r in neural if r['mode']=='draw']),
            'growth_timing':by_growth,'growth_and_food':growth_food,'winning_openings':winners[:12],
            'neural_negative_games':summary([r for r in neural if r['score']<0]),
            'neural_nonnegative_games':summary([r for r in neural if r['score']>=0]),
            'limitations':['Observational associations, not causal effects; checkpoints and opponents differ.',
                           'Two seats from one game and repeated deals are correlated; seat counts are not independent sample counts.',
                           'Card-study factorial treatments are replayed and listed separately, excluded from ordinary-game comparisons.',
                           'Food coverage includes sacrificing seeds/breeding animals; it is an optimistic liquidity bound, not sustainable yield.',
                           'Counts of efficient food actions do not measure every card/conversion opportunity cost.']},allseats

def write_html(path,report):
    def t(rows):
        keys=list(rows[0]) if rows else []
        return '<table><tr>'+''.join('<th>'+html.escape(k)+'</th>' for k in keys)+'</tr>'+''.join('<tr>'+''.join('<td>'+html.escape(str(r[k]))+'</td>' for k in keys)+'</tr>' for r in rows)+'</table>'
    parts=['<!doctype html><meta charset="utf-8"><title>Agricola opening analysis</title><style>body{font:16px system-ui;max-width:1300px;margin:40px auto;padding:24px;background:#faf9f5;color:#243a30}table{border-collapse:collapse;width:100%;margin:20px 0}td,th{padding:10px;border-bottom:1px solid #ccc;text-align:left}th{background:#e3ece3}pre{white-space:pre-wrap}a{color:#175e45}</style><h1>Which openings lead to wins?</h1>',
           '<p>Complete local recording inventory; duplicate trajectories counted once. Each included trajectory passed state-hash, effect-trace and final-score replay checks.</p>',
           '<pre>'+html.escape(json.dumps(report['inventory'],indent=2))+'</pre>']
    for label,key in [('Current-rule neural seats','neural_comparisons'),('Latest model: held-out games only','heldout_comparisons')]:
        parts.append('<h2>'+label+'</h2>')
        parts.append(t([{'Early feature':r['feature'],'Yes: seats':r['yes']['seats'],'Yes: win rate':r['yes']['win_rate'],'Yes: mean score':r['yes']['mean_score'],
                         'No: seats':r['no']['seats'],'No: win rate':r['no']['win_rate'],'No: mean score':r['no']['mean_score']} for r in report[key]]))
    parts+=['<h2>Growth with and without cooking</h2>',
            '<p>Current-rule, two-player neural seats. Cooking means a Fireplace or Cooking Hearth. These associations do not establish causation; checkpoints, opponents and deals differ.</p>',
            t([{'Third worker by round 7':r['third_worker_by_round_7'],'Cooking by round 7':r['cooking_by_round_7'],
                'Seats':r['seats'],'Win rate':f"{r['win_rate']:.1%}",'Mean score':r['mean_score'],'Mean begging':r['mean_begging']}
               for r in report['growth_and_food']]),
            '<h2>First family growth</h2>',t(report['growth_timing']),'<h2>Winning neural openings (25+ points, first growth by round 8)</h2>']
    for row in report['winning_openings']:
        parts+=['<h3>'+str(row['score'])+' points · seat '+str(row['seat']+1)+'</h3>',
                '<p>'+html.escape(row['path'])+'</p>',
                '<p>Growth rounds: '+str(row['growth-rounds'])+'; first kitchen: '+str(row.get('first-kitchen'))+'; begging: '+str(row['begging'])+'</p>',
                t([{'Round':move['round'],'Action':move.get('action-name') or move.get('space') or move.get('type'),
                    'Card / improvement':move.get('card-name') or move.get('improvement-name') or ''} for move in row['opening']])]
    parts+=['<h2>Interpretation limits</h2>','<ul>'+''.join('<li>'+html.escape(x)+'</li>' for x in report['limitations'])+'</ul>']
    path.write_text(''.join(parts))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);parser.add_argument('--workers',type=int,default=6)
    parser.add_argument('--summarize',action='store_true',help='Rebuild reports from an existing verified analysis cache')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=args.summarize);start=time.time();metadata=policies()
    def batch(items,source,op,outfile):
        readers=[];local=threading.local();lock=threading.Lock();output=[]
        def run(item):
            if not hasattr(local,'reader'):
                local.reader=Reader(source)
                with lock:readers.append(local.reader)
            return item,local.reader.request(op=op,path=item['path'])
        try:
            with ThreadPoolExecutor(max_workers=args.workers) as pool, outfile.open('w') as log:
                futures=[pool.submit(run,item) for item in items]
                for i,f in enumerate(as_completed(futures),1):
                    item,result=f.result();result.setdefault('path',item['path']);output.append(result)
                    log.write(json.dumps(result)+'\n');log.flush()
                    if i%100==0 or i==len(items):
                        status={'stage':op,'done':i,'total':len(items),'elapsed_seconds':round(time.time()-start,1)}
                        atomic(args.output/'status.json',status);print(json.dumps(status),flush=True)
        finally:
            for reader in readers:reader.close()
        return output
    files=[]
    for directory in [ROOT/'data',ROOT/'resources/public/recordings']:
        for path in directory.rglob('*.edn'):
            if any('source-baseline' in part for part in path.parts):continue
            with path.open('rb') as f:header=f.readline()
            if b':record :header' in header:files.append({'path':str(path)})
    index=[json.loads(line) for line in (args.output/'inventory.jsonl').open()] if args.summarize else batch(files,ROOT,'index',args.output/'inventory.jsonl')
    by_id={};excluded=[]
    for row in index:
        if row.get('status')!='complete':excluded.append(row);continue
        if row['id'] not in by_id:by_id[row['id']]={**row,'copies':[]}
        by_id[row['id']]['copies'].append(row['path'])
    for row in by_id.values():
        policy=row.get('policies')
        if not policy:
            policy=next((metadata[p] for p in row['copies'] if p in metadata),None)
        row['resolved_policies']=policy if isinstance(policy,list) else [policy or 'unknown']*row['players']
    archives=[ROOT,ROOT/'data/muzero/cards-deep-v1/source-baseline',ROOT/'data/muzero/cards-value-v1.source-baseline']
    sources={}
    for source in archives:
        if not source.exists():continue
        reader=Reader(source)
        try:
            result=reader.request(op='rules')
            if result.get('rules'):sources.setdefault(result['rules'],source)
        finally:reader.close()
    current=next(k for k,v in sources.items() if v==ROOT)
    groups=defaultdict(list)
    for row in by_id.values():groups[row['rules']].append(row)
    games=[];failures=[]
    for version,items in sorted(groups.items(),key=lambda item:-len(item[1])):
        exact=version in sources
        cache=args.output/f'games-{version}.jsonl'
        results=[json.loads(line) for line in cache.open()] if args.summarize else batch(items,sources.get(version,ROOT),'analyze' if exact else 'compatible',cache)
        for row in results:
            if row.get('error'):failures.append(row);continue
            row.update({k:by_id[row['id']][k] for k in ['copies','resolved_policies']});games.append(row)
    report,seats=report_games(games,current)
    reader=Reader(ROOT)
    try:
        report['saved_tables']=reader.request(op='saved-tables',path=str(ROOT/'data/eurozone.edn')) if (ROOT/'data/eurozone.edn').exists() else []
        for winner in report['winning_openings']:
            replayed=reader.request(op='analyze',path=winner['path'])
            if 'error' in replayed:raise RuntimeError(replayed['error'])
            winner['opening']=replayed['seats'][winner['seat']]['opening']
    finally:reader.close()
    report['inventory']={'files':len(index),'complete_files':sum(r.get('status')=='complete' for r in index),
                         'unique_complete_games':len(by_id),'duplicate_copies':sum(len(r['copies'])-1 for r in by_id.values()),
                         'replayed_games':len(games),'replayed_moves':sum(g['moves'] for g in games),'excluded_files':len(excluded),
                         'replay_failures':len(failures),'rules_versions':{k:len(v) for k,v in groups.items()},
                         'report_elapsed_seconds':round(time.time()-start,1),'reused_verified_cache':args.summarize}
    report['excluded']=excluded;report['replay_failures']=failures
    atomic(args.output/'report.json',report)
    with (args.output/'seats.jsonl').open('w') as f:
        for row in seats:f.write(json.dumps(row)+'\n')
    with (args.output/'seats.csv').open('w',newline='') as f:
        keys=['game','path','rules','players','mode','policy','study','seat','score','win','tie','begging','first_growth','first_kitchen','family_after_7','kitchen_after_7']
        writer=csv.DictWriter(f,fieldnames=keys);writer.writeheader()
        for r in seats:
            writer.writerow({**{k:r.get(k) for k in keys},'first_growth':(r['growth-rounds'] or [None])[0],
                             'first_kitchen':r.get('first-kitchen'),'family_after_7':r['snapshots'].get('start-8',{}).get('family'),
                             'kitchen_after_7':r['snapshots'].get('start-8',{}).get('kitchen')})
    write_html(args.output/'report.html',report)
    atomic(args.output/'status.json',{'stage':'complete',**report['inventory']})
    print(json.dumps(report['inventory']),flush=True)

if __name__=='__main__':main()
