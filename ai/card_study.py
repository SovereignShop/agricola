"""Randomized hand values, then controlled factorial confirmation of card combos.

All estimates are conditional on the existing heuristic and two-player rules.
Exploratory co-occurrence coefficients are not presented as causal card values.
Only training/discovery recordings may become neural demonstrations.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import itertools
import json
import os
from pathlib import Path
import selectors
import subprocess
import threading
import time
import numpy as np

ROOT = Path(__file__).resolve().parents[1]

def atomic(path, value):
    tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(value,indent=2));tmp.replace(path)

class Worker:
    def __init__(self):
        self.process=subprocess.Popen(['clojure','-J-Xmx1g','-J-XX:ActiveProcessorCount=2','-M','-m','agricola.card-study'],cwd=ROOT,
                                      stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,bufsize=1)
    def request(self, **message):
        self.process.stdin.write(json.dumps(message)+'\n');self.process.stdin.flush()
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout,selectors.EVENT_READ)
            if not selector.select(300):raise TimeoutError('Card study worker exceeded five minutes')
        line=self.process.stdout.readline()
        if not line:raise RuntimeError('Card study worker exited')
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

def features(rows, ids, pairs):
    index={cid:i for i,cid in enumerate(ids)}
    x=np.zeros((len(rows),len(ids)+1+len(pairs)),np.float64)
    for row,game in enumerate(rows):
        hands=[set(h) for h in game['hands']]
        for pid,sign in [(0,1),(1,-1)]:
            for card in hands[pid]:x[row,index[card]]+=sign
            for j,(a,b) in enumerate(pairs):
                if a in hands[pid] and b in hands[pid]:x[row,len(ids)+1+j]+=sign
        x[row,len(ids)]=1 if game['starting-player']==0 else -1
    return x

def ridge(x,y,penalty):
    # No intercept: exchanging seats negates both features and score margin.
    if x.shape[1] <= x.shape[0]:
        return np.linalg.solve(x.T@x+penalty*np.eye(x.shape[1]),x.T@y)
    return x.T@np.linalg.solve(x@x.T+penalty*np.eye(x.shape[0]),y)

def fit_values(rows, catalog):
    if len(rows)<20:raise ValueError('At least 20 independent deals are needed')
    ids=[c['id'] for c in catalog]
    pairs=list(itertools.combinations(ids,2))
    rng=np.random.default_rng(45102);order=rng.permutation(len(rows))
    split=max(1,int(.8*len(rows)));train,held=order[:split],order[split:]
    x=features(rows,ids,pairs);y=np.array([g['scores'][0]-g['scores'][1] for g in rows],float)
    main=x[:,:len(ids)+1]
    # Stronger shrinkage for interactions; fixed before seeing the held-out deals.
    scale=np.r_[np.ones(len(ids)+1),np.full(len(pairs),.2)]
    coef=ridge((x*scale)[train],y[train],50.)*scale
    main_coef=ridge(main[train],y[train],20.)
    # Coverage/selection use the training partition only.
    counts=np.abs(x[train,len(ids)+1:]).sum(0)
    candidates=[{'pair':list(pair),'coefficient':float(coef[len(ids)+1+j]),'training_deals':int(counts[j])}
                for j,pair in enumerate(pairs) if counts[j]>=max(3,min(12,len(train)//20))]
    candidates.sort(key=lambda row:row['coefficient'],reverse=True)
    # Bootstrap independent training deals for the additive association estimates.
    bootstrap=[]
    for _ in range(100):
        chosen=rng.choice(train,len(train),replace=True)
        bootstrap.append(ridge(main[chosen],y[chosen],20.)[:len(ids)])
    intervals=np.quantile(bootstrap,[.025,.975],axis=0)
    return {'interpretation':'Adjusted dealt-card association with score margin, under the fixed heuristic; not intrinsic/causal points.',
            'train_indices':train.tolist(),'heldout_indices':held.tolist(),
            'heldout_deals':len(held),'main_rmse':float(np.sqrt(np.mean((main[held]@main_coef-y[held])**2))),
            'combo_rmse':float(np.sqrt(np.mean((x[held]@coef-y[held])**2))),
            'cards':sorted([dict(c,association=float(main_coef[i]),interval=intervals[:,i].tolist(),
                                training_deals=int(np.abs(main[train,i]).sum())) for i,c in enumerate(catalog)],
                           key=lambda c:c['association'],reverse=True),
            'candidates':candidates}

def contrasts(result):
    pid=result['focal'];s={k:v['scores'][pid] for k,v in result['cells'].items()}
    return {'synergy':s['AB']-s['Ab']-s['aB']+s['ab'],
            'a_effect':((s['AB']-s['aB'])+(s['Ab']-s['ab']))/2,
            'b_effect':((s['AB']-s['Ab'])+(s['aB']-s['ab']))/2,
            'joint_gain':s['AB']-s['ab']}

def summarize_pairs(results, familywise=False):
    groups={}
    for row in results:groups.setdefault(tuple(row['pair']),[]).append(contrasts(row))
    rng=np.random.default_rng(5112);summaries=[]
    for pair,values in groups.items():
        synergy=np.array([v['synergy'] for v in values],float)
        tail=.025/max(1,len(groups)) if familywise else .025
        interval=np.quantile(rng.choice(synergy,(10000,len(synergy)),replace=True).mean(1),[tail,1-tail]).tolist()
        summaries.append({'pair':list(pair),'deals':len(values),'mean_synergy':float(synergy.mean()),
                          'interval':interval,'interval_kind':'Bonferroni-adjusted bootstrap' if familywise else 'pointwise bootstrap',
                          'mean_joint_gain':float(np.mean([v['joint_gain'] for v in values])),
                          'a_effect':float(np.mean([v['a_effect'] for v in values])),
                          'b_effect':float(np.mean([v['b_effect'] for v in values]))})
    return sorted(summaries,key=lambda r:r['mean_synergy'],reverse=True)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--random-games',type=int,default=1024)
    parser.add_argument('--pairs',type=int,default=64)
    parser.add_argument('--repeats',type=int,default=6)
    parser.add_argument('--confirm-pairs',type=int,default=12)
    parser.add_argument('--confirm-repeats',type=int,default=24)
    parser.add_argument('--workers',type=int,default=4)
    parser.add_argument('--seed',type=int,default=1200000)
    parser.add_argument('--deadline',type=float,default=float('inf'),help='Unix timestamp; finish the current batch before stopping')
    args=parser.parse_args()
    if args.random_games<20 or min(args.pairs,args.repeats,args.confirm_pairs,args.confirm_repeats,args.workers)<1:
        parser.error('Need at least 20 random games and positive study counts')
    args.output.mkdir(parents=True,exist_ok=False);(args.output/'recordings').mkdir()
    workers=[];local=threading.local();lock=threading.Lock();started=time.time()
    report={'config':{k:str(v) if isinstance(v,Path) else v for k,v in vars(args).items() if k!='deadline'},
            'status':'starting','verified_games':0,'verified_moves':0,
            'scope':'Two players; current supported hand cards; fixed heuristic. Confidence intervals are exploratory, not simultaneous guarantees.'}
    log=(args.output/'results.jsonl').open('w')
    def worker():
        if not hasattr(local,'worker'):
            local.worker=Worker()
            with lock:workers.append(local.worker)
        return local.worker
    def batch(stage,jobs):
        out=[]
        # Bound the queue and check the deadline between completed batches.
        for start in range(0,len(jobs),args.workers):
            if time.time()>=args.deadline:break
            futures=[pool.submit(lambda message=message:worker().request(**message)) for message in jobs[start:start+args.workers]]
            for future in as_completed(futures):
                result=future.result();out.append(result)
                cells=list(result['cells'].values()) if 'cells' in result else [result]
                report['verified_games']+=len(cells);report['verified_moves']+=sum(c['moves'] for c in cells)
                log.write(json.dumps({'stage':stage,**result})+'\n');log.flush()
            report.update(status=stage,stage_completed=len(out),stage_total=len(jobs),elapsed_seconds=round(time.time()-started,1))
            atomic(args.output/'status.json',report)
            print(json.dumps({k:report[k] for k in ['status','stage_completed','stage_total','verified_games','elapsed_seconds']}),flush=True)
        return sorted(out,key=lambda r:r['seed'])
    def factorial_jobs(pairs,repeats,offset,stage):
        return [dict(op='factorial',seed=args.seed+offset+i*repeats+j,players=2,pair=pair,
                     output=str((args.output/'recordings'/f'{stage}-{i:03d}-{j:03d}').resolve()))
                for i,pair in enumerate(pairs) for j in range(repeats)]
    try:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            catalog=pool.submit(lambda:worker().request(op='catalog',players=2)).result()
            report['fingerprints']=catalog['fingerprints'];report['catalog']=catalog['cards']
            random=batch('random', [dict(op='random',seed=args.seed+i,players=2,
                         output=str((args.output/'recordings'/f'random-{i:05d}.edn').resolve())) for i in range(args.random_games)])
            values=fit_values(random,catalog['cards']);atomic(args.output/'card-values.json',values)
            pairs=[r['pair'] for r in values['candidates'][:max(1,args.pairs*3//4)]]
            rng=np.random.default_rng(args.seed);allpairs=list(itertools.combinations([c['id'] for c in catalog['cards']],2))
            rng.shuffle(allpairs)
            for pair in allpairs:
                if len(pairs)>=args.pairs:break
                if list(pair) not in pairs:pairs.append(list(pair))
            discovery=batch('discovery',factorial_jobs(pairs,args.repeats,100000,'discovery'))
            discovered=summarize_pairs(discovery);atomic(args.output/'combo-discovery.json',discovered)
            selected=[r['pair'] for r in discovered[:args.confirm_pairs]]
            confirmation=batch('confirmation',factorial_jobs(selected,args.confirm_repeats,200000,'confirmation'))
            confirmed=summarize_pairs(confirmation,familywise=True);atomic(args.output/'combo-confirmation.json',confirmed)
            # Never use estimator holdout or confirmation games as demonstrations.
            candidates=[random[i]['recording'] for i in values['train_indices']]
            candidates += [cell['recording'] for r in discovery for cell in r['cells'].values()]
            rng.shuffle(candidates)
            atomic(args.output/'bootstrap.json',candidates[:192])
            report.update(status='complete' if time.time()<args.deadline else 'budget-finished',
                          main_rmse=values['main_rmse'],combo_rmse=values['combo_rmse'],
                          combos=confirmed,bootstrap_recordings=len(candidates[:192]),elapsed_seconds=round(time.time()-started,1))
            atomic(args.output/'report.json',report);atomic(args.output/'status.json',report)
    except BaseException as error:
        report.update(status='failed',error=str(error));atomic(args.output/'status.json',report);raise
    finally:
        log.close()
        for w in workers:w.close()

if __name__=='__main__':main()
