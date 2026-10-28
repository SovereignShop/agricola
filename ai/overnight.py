"""Run a frozen, bounded overnight study and queue CUDA training after a prior run.

Detached supervisor: logs, progress, immutable sources, and checkpoints survive
the terminal closing. Never interrupts or overwrites the prior experiment.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import traceback

ROOT=Path(__file__).resolve().parents[1]

def atomic(path,value):
    tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(value,indent=2));tmp.replace(path)

def freeze(destination):
    files=[]
    for directory in ['ai','src/clj','src/main/cljc','resources/agricola','resources/rules','resources/eurozone']:
        files.extend(p for p in (ROOT/directory).rglob('*') if p.is_file() and p.suffix in {'.py','.clj','.cljc','.edn','.md','.txt'})
    files.append(ROOT/'deps.edn')
    manifest={}
    for source in sorted(set(files)):
        relative=source.relative_to(ROOT);target=destination/relative
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
        manifest[str(relative)]=hashlib.sha256(target.read_bytes()).hexdigest()
    atomic(destination/'manifest.json',manifest)

def stop(process):
    if process.poll() is None:
        os.killpg(process.pid,signal.SIGTERM)
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--wait-run',type=Path,required=True)
    parser.add_argument('--wait-pid',type=int,required=True)
    parser.add_argument('--until',required=True,help='ISO timestamp including timezone, e.g. 2026-10-26T07:00:00-07:00')
    parser.add_argument('--study-workers',type=int,default=6)
    parser.add_argument('--study-games',type=int,default=1024)
    parser.add_argument('--study-pairs',type=int,default=64)
    parser.add_argument('--study-repeats',type=int,default=6)
    parser.add_argument('--confirm-pairs',type=int,default=12)
    parser.add_argument('--confirm-repeats',type=int,default=24)
    parser.add_argument('--seed',type=int,default=1200000)
    args=parser.parse_args();end=datetime.fromisoformat(args.until)
    if end.tzinfo is None or end.timestamp()-time.time()<7200:parser.error('Choose a timezone-aware end time at least two hours away')
    if args.study_games<20 or min(args.study_workers,args.study_pairs,args.study_repeats,args.confirm_pairs,args.confirm_repeats)<1:
        parser.error('Study counts must be positive, with at least 20 random deals')
    study_seeds=set(range(args.seed,args.seed+args.study_games)) | set(range(args.seed+100000,args.seed+100000+args.study_pairs*args.study_repeats)) | set(range(args.seed+200000,args.seed+200000+args.confirm_pairs*args.confirm_repeats))
    reserved=set(range(2200000,2200032)) | set(range(2201000,2205096)) | set(range(3200000,3200008)) | set(range(4200000,4200016))
    if study_seeds & reserved:parser.error('Study and neural training/evaluation seeds must be disjoint')
    args.output=args.output.resolve();args.wait_run=args.wait_run.resolve()
    args.output.mkdir(parents=True,exist_ok=False)
    source=args.output/'source-baseline';freeze(source)
    python=str(Path(sys.executable).absolute())
    children=[];logs=[]
    status={'pid':os.getpid(),'started':datetime.now().astimezone().isoformat(),'until':args.until,
            'source':str(source),'parent':str(args.wait_run),'parent_pid':args.wait_pid,
            'study':str(args.output/'study'),'training':str(args.output/'training'),
            'status':'starting','warnings':[]}
    def update(stage,**extra):
        status.update(status=stage,**extra);atomic(args.output/'status.json',status)
        print(json.dumps({'status':stage,**extra}),flush=True)
    def launch(label,command):
        log=(args.output/f'{label}.log').open('w');logs.append(log)
        env=dict(os.environ,OMP_NUM_THREADS='1',OPENBLAS_NUM_THREADS='1',MKL_NUM_THREADS='1')
        process=subprocess.Popen(command,cwd=source,env=env,stdin=subprocess.DEVNULL,
                                 stdout=log,stderr=log,start_new_session=True)
        children.append(process)
        atomic(args.output/f'{label}.process.json',{'pid':process.pid,'command':command,'cwd':str(source)})
        return process
    def parent_running():
        # Do not wait for a reused PID belonging to an unrelated process.
        try:
            path=Path(f'/proc/{args.wait_pid}')
            command=(path/'cmdline').read_bytes().decode().split('\0')
            if not any(part.endswith('deep_train.py') for part in command) or '--output' not in command:return False
            output=Path(command[command.index('--output')+1])
            if not output.is_absolute():output=(path/'cwd').resolve()/output
            return output.resolve()==args.wait_run
        except FileNotFoundError:return False
    def interrupted(*_):raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,interrupted)
    try:
        study=args.output/'study'
        command=[python,'-u',str(source/'ai/card_study.py'),'--output',str(study),
                 '--random-games',str(args.study_games),'--pairs',str(args.study_pairs),'--repeats',str(args.study_repeats),
                 '--confirm-pairs',str(args.confirm_pairs),'--confirm-repeats',str(args.confirm_repeats),
                 '--workers',str(args.study_workers),'--seed',str(args.seed),
                 '--deadline',str(end.timestamp()-3*3600)]
        study_process=launch('study',command)
        update('card-study-and-existing-training',study_pid=study_process.pid)
        while study_process.poll() is None or parent_running():
            if time.time()>=end.timestamp()-7200:
                stop(study_process)
                break
            time.sleep(20)
        if parent_running():
            update('not-started',reason='Existing training still active; preserved it without interruption')
            return
        parent=args.wait_run/'best.pt'
        if not parent.exists():raise RuntimeError('Prior run has no selected checkpoint; refusing an unverified substitute')
        parent_report=args.wait_run/'report.json'
        if not parent_report.exists():status['warnings'].append('Prior run exited without its final report; selected validation checkpoint retained')
        good_study=study_process.returncode==0 and (study/'report.json').exists()
        if not good_study:status['warnings'].append('Card study did not finish; training continues without its guidance or examples')
        budget=end.timestamp()-time.time()-3600
        if budget<1200:
            update('not-started',reason='Insufficient time for another evaluated training run');return
        training=args.output/'training'
        command=[python,'-u',str(source/'ai/deep_train.py'),'--resume',str(parent),'--output',str(training),
                 '--device','cuda','--batch-size','48','--enhanced','--curriculum','--draft-fraction','.25',
                 '--card-sample-fraction','.5','--demonstration-fraction','.5','--demonstration-capacity','256',
                 '--learning-rate','.00015','--root-value-weight','.5','--ablate-root-value',
                 '--games','4096','--warmup-games','32','--pretrain-updates','1024','--updates-per-game','32',
                 '--simulations','64','--validation-deals','8','--validation-every','128','--test-deals','16',
                 '--seed','2200000','--validation-seed','3200000','--test-seed','4200000',
                 '--train-seconds',str(round(budget))]
        if good_study:
            command+=['--draft-prior',str(study),'--bootstrap-recordings',*json.loads((study/'bootstrap.json').read_text())]
        process=launch('training',command)
        update('training',training_pid=process.pid,training_budget_seconds=round(budget),study_complete=good_study)
        while process.poll() is None:
            if time.time()>end.timestamp()+1800:
                stop(process);update('time-limit',reason='Evaluation exceeded the overnight window; saved checkpoints retained');return
            time.sleep(20)
        if process.returncode:raise RuntimeError('Queued trainer failed; see training.log and its status.json')
        report=json.loads((training/'report.json').read_text())
        update('complete',training_games=report['training_games'],verified_games=report['verified_games'],updates=report['updates'],
               comparison=report.get('paired_comparison'),draft_comparison=report.get('draft_comparison'))
        atomic(args.output/'report.json',status)
    except BaseException:
        update('failed',error=traceback.format_exc());raise
    finally:
        for process in children:stop(process)
        for log in logs:log.close()

if __name__=='__main__':main()
