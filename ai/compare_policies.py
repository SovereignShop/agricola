"""Fresh paired game evaluation of fixed policies, with one shared action pool."""
import argparse
from concurrent.futures import ProcessPoolExecutor, as_completed
import json
from pathlib import Path
from deep_train import atomic_json, match_summary, paired_summary
from muzero import Replay
from policy_audit import evaluate


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--candidate',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--seed',type=int,default=8300000)
    p.add_argument('--deals',type=int,default=16)
    p.add_argument('--workers',type=int,default=4)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    models=[('baseline',args.baseline,'search'),('candidate',args.candidate,'guarded'),('heuristic',args.baseline,'heuristic')]
    results={name:[] for name,_,_ in models};hashes=set()
    with ProcessPoolExecutor(max_workers=args.workers) as executor:
        pending={}
        for name,checkpoint,variant in models:
            for start in range(0,args.deals,4):
                task=(checkpoint.resolve(),(args.output/name/f'shard-{start:03d}').resolve(),variant,
                      args.seed+start,min(4,args.deals-start),32)
                pending[executor.submit(evaluate,task)]=name
        for future in as_completed(pending):
            report=future.result();name=pending[future]
            results[name].extend(report['results']);hashes.add(report['action_pool_sha256'])
            atomic_json(args.output/'status.json',{'completed':{k:len(v) for k,v in results.items()},
                                                 'planned_per_policy':args.deals*2})
    if len(hashes)!=1:raise ValueError('Evaluation action pools differ')
    for records in results.values():records.sort(key=lambda r:(r['seed'],r['model_seat']))
    report={'status':'complete','seed':args.seed,'independent_deals':args.deals,
            'baseline':str(args.baseline.resolve()),'candidate':str(args.candidate.resolve()),
            'action_pool_sha256':next(iter(hashes)),
            'summary':{name:match_summary(records) for name,records in results.items()},
            'candidate_vs_baseline':paired_summary(results['baseline'],results['candidate']),
            'candidate_vs_heuristic':paired_summary(results['heuristic'],results['candidate']),
            'results':results}
    atomic_json(args.output/'report.json',report)
    print(json.dumps({k:v for k,v in report.items() if k!='results'},indent=2))


if __name__=='__main__':main()
