import unittest
from afterstate_value import feature_names


class TrainingFeatureVocabulary(unittest.TestCase):
    def test_validation_features_cannot_change_fitted_vocabulary(self):
        rows = [{'seed': 1, 'features': {'base/bias': 1, 'card/train': 1}},
                {'seed': 2, 'features': {'base/bias': 1, 'pair/held-only': 1}}]
        self.assertEqual(feature_names(rows, [0]), ['base/bias', 'card/train'])
        rows[1]['features'] = {'pair/another-held-only': 1e9}
        self.assertEqual(feature_names(rows, [0]), ['base/bias', 'card/train'])


if __name__ == '__main__':
    unittest.main()
