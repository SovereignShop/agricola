"""Experimental draft teacher: shrink dealt-card estimates and confirmed synergies.

Consumes only the acting player's visible selected cards and engine-legal picks.
The existing heuristic remains an anchor; evaluation opponents are unchanged.
"""
import hashlib
import json
from pathlib import Path
import numpy as np
from edn_format import loads, Keyword
from muzero import Environment
from rewards import RewardShaper

class DraftPrior:
    def __init__(self, directory):
        directory=Path(directory)
        paths=[directory/'card-values.json',directory/'combo-confirmation.json']
        self.fingerprint=hashlib.sha256(b''.join(p.read_bytes() for p in paths)).hexdigest()
        self.cards={row['id']:row for row in json.loads(paths[0].read_text())['cards']}
        self.combos={tuple(sorted(row['pair'])):row for row in json.loads(paths[1].read_text())
                     if row['deals']>=12 and row['interval'][0]>0}
    def choose(self,response):
        original=response['expert']
        selected=set()
        for path,value in response['observation']:
            if value and path.startswith('[:draw-private :selected '):
                item=loads(path)[-1]
                if isinstance(item,Keyword) and '/' in str(item):selected.add(str(item))
        scored=[]
        for i,action in enumerate(response['actions']):
            command=loads(action['edn']);card=str(command.get(Keyword('card')))
            row=self.cards.get(card)
            # Low coverage does not displace the existing teacher.
            value=2. if i==original else 0.
            if row and row['training_deals']>=30:
                value+=float(np.clip(row['association'],-6,6))*.5
                combo=sum(min(4.,self.combos[key]['interval'][0]) for other in selected
                          if (key:=tuple(sorted((card,other)))) in self.combos)
                value+=min(6.,combo)
            scored.append(value)
        return int(np.argmax(scored))

class GuidedEnvironment(Environment):
    def __init__(self,prior=None,reward_shaping='none'):
        super().__init__();self.prior=prior;self.guidance_enabled=False;self.shaper=RewardShaper(reward_shaping)
    def request(self,**message):
        response=super().request(**message)
        if self.prior and self.guidance_enabled and message.get('expert') and response.get('phase')=='draw' and 'expert' in response:
            response['expert']=self.prior.choose(response)
        return self.shaper.apply(message.get('op'),response)
