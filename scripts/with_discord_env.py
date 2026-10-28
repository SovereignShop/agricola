"""Run a command with .env values without treating the file as executable shell code."""
import os
from pathlib import Path
import sys

root=Path(__file__).resolve().parents[1]
env=dict(os.environ)
path=root/'.env'
if path.exists():
    for line in path.read_text().splitlines():
        line=line.strip()
        if not line or line.startswith('#'): continue
        key,sep,value=line.partition('=')
        if not sep or not key.replace('_','').isalnum(): raise SystemExit('Invalid .env assignment')
        if len(value)>1 and value[0]==value[-1] and value[0] in ['"',"'"]: value=value[1:-1]
        if value: env.setdefault(key,value)
command=sys.argv[1:] or ['clojure','-M:run',env.get('PORT','3002')]
os.chdir(root)
os.execvpe(command[0], command, env)
