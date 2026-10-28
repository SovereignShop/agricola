import unittest
import numpy as np
from card_expectations import fit_formulas, paired

class CardExpectationsTests(unittest.TestCase):
    def test_validation_results_cannot_change_learned_ranks_or_pairs(self):
        ids=list('ABCDEF');rng=np.random.default_rng(21)
        rows=[]
        for i in range(100):
            order=rng.permutation(ids).tolist();hands=[order[:2],order[2:4]]
            scores=[10+3*('A' in hand)-2*('B' in hand)+6*({'C','D'}<=set(hand)) for hand in hands]
            rows.append({'hands':hands,'scores':scores,'starting-player':i%2})
        catalog=[{'id':c,'name':c,'kind':'minor' if i<3 else 'occupation','printed-cost':{'wood':i%2},'heuristic':float(i)} for i,c in enumerate(ids)]
        train=np.arange(80);held=np.arange(80,100)
        before=fit_formulas(rows,catalog,train,held)
        for i in held:rows[i]['scores']=[1000+i,-1000]
        after=fit_formulas(rows,catalog,train,held)
        self.assertEqual(before['models'],after['models'])
        self.assertEqual(before['cross_validation'],after['cross_validation'])
        self.assertNotEqual(before['predictive'],after['predictive'])
        self.assertGreater(before['models']['adjusted']['main']['A'],before['models']['adjusted']['main']['B'])

    def test_paired_intervals_group_both_seats_by_independent_deal(self):
        base=[];candidate=[]
        for seed in range(6):
            for seat in range(2):
                scores=[10,10];scores[seat]+=3
                base.append({'seed':seed,'model_seat':seat,'scores':[10,10]})
                candidate.append({'seed':seed,'model_seat':seat,'scores':scores})
        result=paired(base,candidate)
        self.assertEqual(result['independent_deals'],6)
        self.assertEqual(result['score_gain_95_interval'],[3.,3.])
        self.assertEqual(result['margin_gain_95_interval'],[3.,3.])
        with self.assertRaises(ValueError):paired(base,candidate[:-1])

if __name__=='__main__':unittest.main()
