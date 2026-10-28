"""Potential-based public-state feedback; raw game scores remain the objective."""
import numpy as np

VERSION='food-growth-v1'
SCALE=50.

class RewardShaper:
    def __init__(self,version='none'):
        if version not in ['none',VERSION]:raise ValueError('Unknown learning reward scheme')
        self.version=version;self.previous=None;self.fingerprint=None
    def apply(self,op,response):
        if op not in ['reset','load-recording','step']:return response
        if self.version=='none':return response
        learning=response.get('learning',{})
        if learning.get('version')!=self.version:raise ValueError('Engine does not provide the requested reward scheme')
        fingerprint=learning['fingerprint']
        if self.fingerprint is not None and fingerprint!=self.fingerprint:raise ValueError('Learning reward definition changed mid-run')
        self.fingerprint=fingerprint
        potential=np.asarray(learning['potentials'],dtype=float)/SCALE
        if not np.isfinite(potential).all():raise ValueError('Nonfinite reward potential')
        if response['terminal'] and np.any(potential):raise ValueError('Terminal shaping potential must be zero')
        response=dict(response,raw_reward=response['reward'])
        if op=='step':
            if self.previous is None or self.previous.shape!=potential.shape:raise ValueError('Reset before shaping game transitions')
            response['reward']=(np.asarray(response['reward'])+potential-self.previous).tolist()
        self.previous=potential
        return response
