import unittest
import numpy as np
from growth_learning import design


class GrowthPairs(unittest.TestCase):
    def test_pair_orientation_and_training_only_vocabulary(self):
        rows = [{'a': {'features': {'growth/time': .6}, 'parent_score': 31.},
                 'b': {'features': {'growth/time': .2}, 'parent_score': 28.}, 'score_difference': 7.},
                {'a': {'features': {'hidden-validation-only': 900.}, 'parent_score': 10.},
                 'b': {'features': {}, 'parent_score': 9.}, 'score_difference': 2.}]
        names, x, y, _, _ = design(rows, [0])
        self.assertEqual(names, ['growth/time'])
        self.assertAlmostEqual(x[0, 0], .4)
        self.assertEqual(y[0], 4.)
        reverse = {**rows[0], 'a': rows[0]['b'], 'b': rows[0]['a'], 'score_difference': -7.}
        _, rx, ry, _, _ = design([reverse], [0])
        np.testing.assert_allclose(rx[0], -x[0])
        self.assertEqual(ry[0], -y[0])


if __name__ == '__main__': unittest.main()
