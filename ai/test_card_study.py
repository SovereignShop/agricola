import unittest
import numpy as np
from draft_prior import DraftPrior
from card_study import contrasts, features, ridge, summarize_pairs

class CardStudyTests(unittest.TestCase):
    def test_factorial_cancels_individual_card_strength_and_baseline(self):
        def result(interaction):
            return {'pair':['A','B'],'focal':1,'cells':{k:{'scores':[999,v]} for k,v in
                    {'ab':20,'Ab':23,'aB':25,'AB':28+interaction}.items()}}
        self.assertEqual(contrasts(result(0))['synergy'],0)
        self.assertEqual(contrasts(result(7))['synergy'],7)
        rows=summarize_pairs([result(7),result(7)])
        self.assertEqual(rows[0]['interval'],[7,7])
    def test_features_see_dealt_hands_and_reverse_with_seats(self):
        rows=[{'hands':[['A','B'],['C','D']],'starting-player':0},
              {'hands':[['C','D'],['A','B']],'starting-player':1}]
        x=features(rows,['A','B','C','D'],[('A','B'),('A','C')])
        np.testing.assert_array_equal(x[0],-x[1])
        self.assertEqual(x[0,-2],1);self.assertEqual(x[0,-1],0)
    def test_ridge_recovers_synthetic_interactions_without_nan(self):
        rng=np.random.default_rng(3);x=rng.normal(size=(500,12));weights=np.arange(12)/4
        predicted=ridge(x,x@weights,.01)
        np.testing.assert_allclose(predicted,weights,atol=.001)
        # Dual branch for underdetermined hands, still regularized.
        y=x[:8]@weights;fit=ridge(x[:8],y,.001)
        np.testing.assert_allclose(x[:8]@fit,y,atol=.01)
    def test_combo_draft_teacher_only_uses_own_selected_cards(self):
        prior=DraftPrior.__new__(DraftPrior)
        prior.cards={':test/A':{'training_deals':100,'association':0},':test/B':{'training_deals':100,'association':0}}
        prior.combos={(':test/B',':test/C'):{'interval':[4,8]}}
        response={'expert':0,'actions':[{'edn':'{:type :pick :card :test/A}'},{'edn':'{:type :pick :card :test/B}'}],
                  'observation':[['[:players 1 :hand :minor 0 :test/C]',1]]}
        self.assertEqual(prior.choose(response),0)
        response['observation'].append(['[:draw-private :selected :minor 0 :test/C]',1])
        self.assertEqual(prior.choose(response),1)

if __name__=='__main__':unittest.main()
