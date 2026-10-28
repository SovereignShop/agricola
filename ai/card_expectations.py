"""Compare card EV formulas, then test their draft choices on independent games.

Fit only randomized-deal training rows. Keep their original held-out partition.
Select a formula using fresh development drafts, and test it once on new deals.
These are policy-dependent associations, not intrinsic or causal card values.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import csv
import hashlib
import html
import itertools
import json
import os
from pathlib import Path
import selectors
import subprocess
import threading
import time
import numpy as np
from card_study import features, ridge, atomic
from overnight import freeze

ROOT = Path(__file__).resolve().parents[1]
FORMULAS = ['heuristic', 'mean', 'shrunk', 'adjusted', 'conservative', 'conditional', 'efficiency']
DESCRIPTIONS = {
    'heuristic': 'Existing printed-effect heuristic: trigger/passive/ability counts, points, cost and prerequisites.',
    'mean': 'Mean focal score margin in training deals where this card was dealt to that player.',
    'shrunk': 'Mean margin × n/(n+50): shrink sparsely observed cards toward zero.',
    'adjusted': 'Ridge marginal contribution to score margin, controlling all dealt cards and starting player.',
    'conservative': 'Adjusted contribution minus one bootstrap standard deviation; a conservative decision index.',
    'conditional': 'Joint ridge main effect + pair coefficients with cards already selected in this draft.',
    'efficiency': 'Adjusted contribution / (1 + 0.35 × printed resource count + 0.5 for occupations); a value-per-cost index, not additional points.',
}

class Worker:
    def __init__(self, root):
        self.process = subprocess.Popen(['clojure','-J-Xmx1g','-J-XX:ActiveProcessorCount=2','-M','-m','agricola.card-rank-experiment'],
            cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,bufsize=1)
    def request(self, **message):
        self.process.stdin.write(json.dumps(message)+'\n');self.process.stdin.flush()
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout,selectors.EVENT_READ)
            if not selector.select(300):raise TimeoutError('Ranking worker exceeded five minutes')
        line=self.process.stdout.readline()
        if not line:raise RuntimeError('Ranking worker exited')
        result=json.loads(line)
        if 'error' in result:raise RuntimeError(result['error'])
        return result
    def close(self):
        self.process.stdin.close()
        try:self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait()

def select_ridge(x, y, train, options, scales):
    # All hyperparameter selection stays inside the original training partition.
    order=np.random.default_rng(91041).permutation(train)
    folds=np.array_split(order,4);rows=[]
    for penalty in options:
        for scale in scales:
            errors=[]
            for held in folds:
                fit=np.setdiff1d(train,held)
                coef=ridge((x*scale)[fit],y[fit],penalty)*scale
                errors.extend((x[held]@coef-y[held])**2)
            rows.append((float(np.mean(errors)),penalty,scale))
    best=min(rows,key=lambda row:row[0])
    coef=ridge((x*best[2])[train],y[train],best[1])*best[2]
    return coef,{'penalty':best[1],'pair_scale':float(best[2][-1]),'training_cv_rmse':float(np.sqrt(best[0]))}

def fit_formulas(rows, catalog, train, held):
    ids=[card['id'] for card in catalog];n=len(ids);pairs=list(itertools.combinations(ids,2))
    x=features(rows,ids,pairs);main=x[:,:n+1]
    y=np.array([r['scores'][0]-r['scores'][1] for r in rows],float)
    exposure=(np.abs(main[train,:n])>0).sum(0)
    means=(main[train,:n].T@y[train])/np.maximum(1,exposure)
    additive,add_info=select_ridge(main,y,train,[10.,30.,100.],[np.ones(n+1)])
    joint,joint_info=select_ridge(x,y,train,[30.],[np.r_[np.ones(n+1),np.full(len(pairs),v)] for v in [.1,.25,.5]])
    rng=np.random.default_rng(91042)
    boot=np.stack([ridge(main[chosen],y[chosen],add_info['penalty'])[:n]
                   for chosen in (rng.choice(train,len(train),replace=True) for _ in range(200))])
    uncertainty=boot.std(0,ddof=1)
    cost=np.array([1+.35*sum(c.get('printed-cost',{}).values())+.5*(c['kind']=='occupation') for c in catalog])
    vectors={'heuristic':np.array([c['heuristic'] for c in catalog]),'mean':means,
             'shrunk':means*exposure/(exposure+50),'adjusted':additive[:n],
             'conservative':additive[:n]-uncertainty,'conditional':joint[:n],
             'efficiency':additive[:n]/cost}
    models={name:{'main':dict(zip(ids,map(float,value))),'pairs':{},'description':DESCRIPTIONS[name]} for name,value in vectors.items()}
    for (a,b),v in zip(pairs,joint[n+1:]):
        models['conditional']['pairs'].setdefault(a,{})[b]=float(v)
        models['conditional']['pairs'].setdefault(b,{})[a]=float(v)
    predictive={}
    # Calibrate scale on training rows for heuristic/index formulas, since their
    # raw units differ. This scalar is only for RMSE reporting, not draft ranks.
    for name,v in vectors.items():
        if name=='adjusted':pred=main@additive
        elif name=='conditional':pred=x@joint
        else:
            z=np.c_[main[:,:n]@v,main[:,n]]
            scale=np.linalg.lstsq(z[train],y[train],rcond=None)[0];pred=z@scale
        predictive[name]={'validation_rmse':float(np.sqrt(np.mean((pred[held]-y[held])**2)))}
    ranking=[]
    for i,card in enumerate(catalog):
        ranking.append({**card,'training_exposures':int(exposure[i]),'adjusted_bootstrap_sd':float(uncertainty[i]),
                        'adjusted_95_interval':np.quantile(boot[:,i],[.025,.975]).tolist(),
                        'values':{name:float(v[i]) for name,v in vectors.items()}})
    for card in ranking:
        card['ranks']={name:1+sum(other['kind']==card['kind'] and other['values'][name]>card['values'][name] for other in ranking) for name in vectors}
    pair_exposure=np.abs(x[train,n+1:]).sum(0)
    interactions=sorted([{'cards':[a,b],'coefficient':float(v),'training_deals':int(count)}
                         for (a,b),v,count in zip(pairs,joint[n+1:],pair_exposure)],key=lambda p:p['coefficient'],reverse=True)
    return {'models':models,'ranking':ranking,'predictive':predictive,
            'cross_validation':{'adjusted':add_info,'conditional':joint_info},
            'exploratory_interactions':interactions[:30],
            'zero_margin_validation_rmse':float(np.sqrt(np.mean(y[held]**2)))}

def summary(rows):
    scores=[r['scores'][r['model_seat']] for r in rows]
    margins=[r['scores'][r['model_seat']]-r['scores'][1-r['model_seat']] for r in rows]
    return {'games':len(rows),'mean_score':float(np.mean(scores)),'median_score':float(np.median(scores)),
            'max_score':max(scores),'mean_margin':float(np.mean(margins)),
            'wins':sum(r['winners']==[r['model_seat']] for r in rows),'draws':sum(len(r['winners'])>1 for r in rows),
            'mean_begging':float(np.mean([r['begging'][r['model_seat']] for r in rows]))}

def paired(baseline, candidate):
    base={(r['seed'],r['model_seat']):r for r in baseline}
    current={(r['seed'],r['model_seat']):r for r in candidate}
    if set(base)!=set(current):raise ValueError('Comparison needs exactly matched deals and seats')
    differences={}
    for key,row in current.items():
        seed,seat=key;ref=base[key];other=1-seat
        differences.setdefault(seed,[]).append([row['scores'][seat]-ref['scores'][seat],
            (row['scores'][seat]-row['scores'][other])-(ref['scores'][seat]-ref['scores'][other])])
    units=np.array([np.mean(differences[seed],axis=0) for seed in sorted(differences)])
    rng=np.random.default_rng(91043)
    boot=units[rng.integers(0,len(units),size=(10000,len(units)))].mean(1)
    return {'independent_deals':len(units),'score_gain':float(units[:,0].mean()),
            'score_gain_95_interval':np.quantile(boot[:,0],[.025,.975]).tolist(),
            'margin_gain':float(units[:,1].mean()),'margin_gain_95_interval':np.quantile(boot[:,1],[.025,.975]).tolist()}

def report_html(output, report):
    ranking=report['fit']['ranking'];by_id={c['id']:c['name'] for c in ranking}
    parts=['<!doctype html><meta charset="utf-8"><title>Eurozone card expected values</title>',
           '<style>body{font:16px system-ui;max-width:1200px;margin:40px auto;padding:0 20px;color:#23352b}table{border-collapse:collapse;width:100%;margin:20px 0}td,th{padding:8px;border-bottom:1px solid #ddd;text-align:left}small{color:#526359}code{background:#eef2ec}h2{margin-top:40px}</style>',
           '<h1>Card expected-value experiments</h1><p>Two-player Revised Edition; 82 eligible cards. Estimates depend on the fixed gameplay heuristic. Dealt-card associations are not intrinsic or causal card points. Pair coefficients are exploratory, not confirmed combos.</p>',
           '<p>Original randomized dataset: 819 training deals / 205 validation deals. Formula tuning uses training-only cross-validation. Fresh drafts select the policy; a separate fresh test evaluates the selected policy once. Both seats are grouped by deal for confidence intervals.</p>']
    if 'test' in report:
        parts+=['<h2>Fresh final draft test</h2><pre>'+html.escape(json.dumps(report['test'],indent=2))+'</pre>']
    if 'development' in report:
        parts+=['<h2>Development games (used for selection)</h2><table><tr><th>Formula</th><th>Mean score</th><th>Mean margin</th><th>Wins / games</th></tr>']
        for name,result in report['development'].items():
            s=result['summary'];parts.append(f'<tr><td>{name}</td><td>{s["mean_score"]:.2f}</td><td>{s["mean_margin"]:.2f}</td><td>{s["wins"]}/{s["games"]}</td></tr>')
        parts.append('</table>')
    for name in FORMULAS:
        parts += [f'<h2>{name}</h2><p>{html.escape(DESCRIPTIONS[name])}</p>',
                  f'<small>Random-hand validation margin RMSE: {report["fit"]["predictive"][name]["validation_rmse"]:.3f}. Conditional rankings below assume no cards selected yet; pair terms change them during the draft.</small>',
                  '<table><tr><th>Rank</th><th>Minor improvement</th><th>Index</th><th>Occupation</th><th>Index</th></tr>']
        ranks={kind:sorted([c for c in ranking if c['kind']==kind],key=lambda c:c['values'][name],reverse=True)[:10] for kind in ['minor','occupation']}
        for i,(a,b) in enumerate(zip(ranks['minor'],ranks['occupation']),1):
            parts.append(f'<tr><td>{i}</td><td>{html.escape(a["name"])}</td><td>{a["values"][name]:.2f}</td><td>{html.escape(b["name"])}</td><td>{b["values"][name]:.2f}</td></tr>')
        parts.append('</table>')
    parts+=['<h2>Exploratory pair terms</h2><p>These coefficients guide conditional draft choices. They have not passed independent factorial confirmation.</p><table><tr><th>Pair</th><th>Coefficient</th><th>Training exposure</th></tr>']
    for pair in report['fit']['exploratory_interactions'][:10]:
        parts.append(f'<tr><td>{html.escape(" + ".join(by_id[c] for c in pair["cards"]))}</td><td>{pair["coefficient"]:.3f}</td><td>{pair["training_deals"]}</td></tr>')
    parts.append('</table>')
    (output/'report.html').write_text('\n'.join(parts))

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--study',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--dev-deals',type=int,default=8);p.add_argument('--test-deals',type=int,default=24)
    p.add_argument('--workers',type=int,default=4);p.add_argument('--seed',type=int,default=9000000)
    args=p.parse_args()
    if min(args.dev_deals,args.test_deals,args.workers)<1 or args.dev_deals>=100000:p.error('Invalid counts')
    args.output=args.output.resolve();args.study=args.study.resolve();args.output.mkdir(parents=True,exist_ok=False)
    frozen=args.output/'source-baseline';freeze(frozen)
    workers=[];local=threading.local();lock=threading.Lock();started=time.time()
    report={'status':'fitting','selection_metric':'development mean score margin','seed':args.seed,
            'development_deals':args.dev_deals,'test_deals':args.test_deals,'source':str(frozen),'warnings':[
                'Only two-player drafts. Gameplay after drafting stays the same heuristic.',
                'Card estimates are policy-dependent associations. Pair terms are exploratory.',
                'Original dataset validation has been inspected in prior studies; final draft test deals are fresh.']}
    def save():
        report['elapsed_seconds']=round(time.time()-started,1);atomic(args.output/'report.json',report)
        atomic(args.output/'status.json',{k:v for k,v in report.items() if k not in ['fit','results']})
        if 'fit' in report:report_html(args.output,report)
    def worker():
        if not hasattr(local,'worker'):
            local.worker=Worker(frozen)
            with lock:workers.append(local.worker)
        return local.worker
    try:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            catalog=pool.submit(lambda:worker().request(op='catalog')).result()
            old=json.loads((args.study/'card-values.json').read_text());old_report=json.loads((args.study/'report.json').read_text())
            if old_report['fingerprints']!=catalog['fingerprints']:raise ValueError('Study fingerprints differ from current rules/actions')
            # Original fit_values sorted by seed before splitting. Preserve it.
            rows=sorted([r for r in map(json.loads,(args.study/'results.jsonl').read_text().splitlines()) if r['stage']=='random'],key=lambda r:r['seed'])
            train=np.array(old['train_indices']);held=np.array(old['heldout_indices'])
            if set(train)&set(held) or set(train)|set(held)!=set(range(len(rows))):raise ValueError('Invalid study partition')
            if any(r['replay']!='complete' for r in rows):raise ValueError('Unverified training game')
            report['dataset']={'training_deals':len(train),'validation_deals':len(held),'sha256':hashlib.sha256((args.study/'results.jsonl').read_bytes()).hexdigest(),
                               'training_seeds':[rows[i]['seed'] for i in train],'validation_seeds':[rows[i]['seed'] for i in held]}
            fitted=fit_formulas(rows,catalog['cards'],train,held)
            models={'rules':catalog['fingerprints']['rules'],'models':{k:v for k,v in fitted.pop('models').items() if k!='heuristic'}}
            model_path=args.output/'models.json';atomic(model_path,models)
            report['fit']=fitted;save()
            with (args.output/'rankings.csv').open('w') as f:
                writer=csv.writer(f);writer.writerow(['id','name','kind','training_exposures']+FORMULAS+['rank_'+name for name in FORMULAS])
                for c in fitted['ranking']:writer.writerow([c['id'],c['name'],c['kind'],c['training_exposures']]+[c['values'][name] for name in FORMULAS]+[c['ranks'][name] for name in FORMULAS])
            def evaluate(stage, formulas, seed, deals):
                report['status']=stage;results={name:[] for name in formulas}
                jobs=[dict(op='game',formula=name,seed=seed+deal,focal=seat,models=str(model_path),
                           output=str(args.output/'recordings'/stage/name/f'{deal:03d}-{seat}.edn'))
                      for deal in range(deals) for seat in range(2) for name in formulas]
                for begin in range(0,len(jobs),args.workers):
                    tasks=[pool.submit(lambda job=job:worker().request(**job)) for job in jobs[begin:begin+args.workers]]
                    for future in as_completed(tasks):
                        row=future.result();results[row['formula']].append(row)
                        with (args.output/'games.jsonl').open('a') as f:f.write(json.dumps({'stage':stage,**row})+'\n')
                    report['progress']={'completed':sum(map(len,results.values())),'planned':len(jobs)};save()
                    print(json.dumps({'stage':stage,**report['progress'],'seconds':report['elapsed_seconds']}),flush=True)
                for rows in results.values():rows.sort(key=lambda r:(r['seed'],r['model_seat']))
                return results
            dev=evaluate('development',FORMULAS,args.seed,args.dev_deals)
            report['development']={name:{'summary':summary(rows),'paired_vs_heuristic':paired(dev['heuristic'],rows)} for name,rows in dev.items()}
            selected=max((name for name in FORMULAS if name!='heuristic'),key=lambda name:report['development'][name]['summary']['mean_margin'])
            report['selected']=selected;save()
            final=evaluate('test',['heuristic',selected],args.seed+100000,args.test_deals)
            report['test']={'selected':selected,'summary':{name:summary(rows) for name,rows in final.items()},'paired_vs_heuristic':paired(final['heuristic'],final[selected])}
            report['results']={'development':dev,'test':final};report['status']='complete';save()
            print(json.dumps({'selected':selected,'test':report['test']},indent=2))
    except BaseException as e:
        report.update(status='failed',error=str(e));save();raise
    finally:
        for w in workers:w.close()

if __name__=='__main__':main()
