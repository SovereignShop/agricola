import unittest
import numpy as np
from rewards import RewardShaper,VERSION

def response(potential,reward=None,terminal=False):
    return {'terminal':terminal,'reward':reward or [0.,0.],
            'learning':{'version':VERSION,'fingerprint':'fixture','potentials':potential}}

class RewardTests(unittest.TestCase):
    def test_dense_credit_telescopes_to_actual_scores_minus_initial_constant(self):
        shaper=RewardShaper(VERSION);shaper.apply('reset',response([1.,1.5]))
        total=np.zeros(2)
        for p in [[5.,3.],[12.,-2.],[6.,4.]]:
            total+=shaper.apply('step',response(p))['reward']
        final=shaper.apply('step',response([0.,0.],[.6,-.4],True))
        total+=final['reward']
        np.testing.assert_allclose(total,np.array([.6,-.4])-np.array([1.,1.5])/50)
        self.assertEqual(final['raw_reward'],[.6,-.4])
    def test_no_positive_reward_from_repeatable_potential_cycle(self):
        s=RewardShaper(VERSION);s.apply('reset',response([2,1]))
        a=s.apply('step',response([5,4]))['reward'];b=s.apply('step',response([2,1]))['reward']
        np.testing.assert_allclose(np.array(a)+b,0,atol=1e-12)
    def test_terminal_and_definition_guards_and_reset(self):
        s=RewardShaper(VERSION);s.apply('reset',response([2,1]))
        with self.assertRaises(ValueError):s.apply('step',response([1,0],terminal=True))
        changed=response([2,1]);changed['learning']['fingerprint']='different'
        with self.assertRaises(ValueError):s.apply('step',changed)
        s.apply('load-recording',response([7,8]))
        np.testing.assert_allclose(s.apply('step',response([7,8]))['reward'],[0,0])
        raw=response([2,1],[.2,.3]);self.assertIs(RewardShaper().apply('step',raw),raw)
