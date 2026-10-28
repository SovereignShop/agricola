import unittest
import numpy as np
import torch
from action_rank import design, Ranker, portable, metrics


class PairedRankTests(unittest.TestCase):
    def test_split_vocabulary_orientation_and_equal_case_weight(self):
        a = {'features': {'family': .6}, 'parent-score': 31.}
        b = {'features': {'family': .4}, 'parent-score': 30.}
        rows = [{'case': 1, 'a': a, 'b': b, 'margin_difference': 4.},
                {'case': 1, 'a': b, 'b': a, 'margin_difference': -4.},
                {'case': 2, 'a': {'features': {'held-only': 42.}, 'parent-score': 10.},
                 'b': b, 'margin_difference': -3.}]
        names, x, parent, y, w, train, held = design(rows, {1})
        self.assertEqual(names, ['family'])
        np.testing.assert_allclose(x[0, 0]-x[0, 1], -(x[1, 0]-x[1, 1]))
        self.assertEqual(parent[0], -parent[1]); self.assertEqual(y[0], -y[1])
        self.assertEqual(w[train].sum(), w[held].sum())
        self.assertEqual(metrics(y, y, w)['regret'], 0.)

    def test_portable_nonlinear_predictions(self):
        torch.manual_seed(7)
        for width in [0, 24]:
            model = Ranker(8, width)
            for p in model.parameters(): torch.nn.init.uniform_(p, -.5, .5)
            x = torch.randn(20, 8)
            np.testing.assert_allclose(model(x).detach().numpy(), portable(model.portable(), x.numpy()), atol=1e-6)


if __name__ == '__main__': unittest.main()
