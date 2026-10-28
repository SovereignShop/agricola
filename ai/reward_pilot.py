"""Matched raw-score versus food/growth shaping pilot, with frozen sources."""
import argparse
import json
from pathlib import Path
import subprocess
import signal
import sys
import time
from overnight import freeze,atomic,stop
from deep_train import paired_summary

ROOT=Path(__file__).resolve().parents[1]

def select_demonstrations(analysis,limit=32):
    rows=[json.loads(line) for line in (analysis/'seats.jsonl').open()]
    grouped={}
    for row in rows:
        if row['players']==2 and row['mode']=='random' and row['rules'].startswith('694dc119') and row['study']!='factorial':
            grouped.setdefault(row['game'],[]).append(row)
    good=[]
    for game,players in grouped.items():
        path=players[0]['path']
        if not Path(path).name.startswith(('training-','warmup-','random-')):continue
        if len(players)!=2 or not all(p['score']>=15 and p['begging']<=1 for p in players):continue
        early=min((p['growth-rounds'] or [15])[0] for p in players)
        if early<=8:good.append((early,-sum(p['score'] for p in players),path))
    # Mix examples from different deals; both players must be useful teachers.
    selected=sorted(good)[:limit]
    if len(selected)<8:raise ValueError('Too few complete, well-fed teaching games')
    return [path for _,_,path in selected]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--resume',type=Path,required=True)
    parser.add_argument('--analysis',type=Path,required=True)
    parser.add_argument('--games',type=int,default=64)
    args=parser.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    source=out/'source-baseline';freeze(source)
    demos=select_demonstrations(args.analysis)
    atomic(out/'demonstrations.json',demos)
    config={'status':'starting','started':time.time(),'games_per_arm':args.games,'demonstrations':len(demos),
            'resume':str(args.resume.resolve()),'source':str(source),'arms':['none','food-growth-v1']}
    atomic(out/'status.json',config)
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f"Interrupted by signal {signum}")
    signal.signal(signal.SIGTERM, interrupted)
    process=None
    try:
        for label,scheme in [('control','none'),('shaped','food-growth-v1')]:
            command=[sys.executable,'-u',str(source/'ai/deep_train.py'),'--resume',str(args.resume.resolve()),
                     '--output',str(out/label),'--transfer','--enhanced','--device','cuda','--batch-size','48',
                     '--curriculum','--demonstration-fraction','.5','--learning-rate','.00015',
                     '--games',str(args.games),'--warmup-games','16','--pretrain-updates','512','--updates-per-game','16',
                     '--simulations','32','--validation-deals','8','--validation-every','64','--test-deals','16',
                     '--seed','5200000','--validation-seed','6200000','--test-seed','7200000',
                     '--reward-shaping',scheme,'--bootstrap-recordings',*demos]
            with (out/f'{label}.log').open('w') as log:
                process=subprocess.Popen(command,cwd=source,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
                config.update(status=label,pid=process.pid);atomic(out/'status.json',config)
                atomic(out/f'{label}.process.json',{'pid':process.pid,'command':command})
                if process.wait():raise RuntimeError(f'{label} failed; inspect its log')
        reports={key:json.loads((out/key/'report.json').read_text()) for key in ['control','shaped']}
        comparison=paired_summary(reports['control']['evaluation']['best']['results'],reports['shaped']['evaluation']['best']['results'])
        config.update(status='complete',comparison=comparison,
                      evaluations={key:r['evaluation']['best']['summary'] for key,r in reports.items()})
        atomic(out/'report.json',config);atomic(out/'status.json',config)
    except BaseException as error:
        config.update(status='failed',error=str(error));atomic(out/'status.json',config);raise
    finally:
        if process:stop(process)

if __name__=='__main__':main()
