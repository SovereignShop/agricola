import unittest
import numpy as np
import torch
from encoding import encode, OBS_DIM, ACTION_DIM, SEATS
from muzero import Network, Node, Replay, backup, search, train_batch


class MuZeroTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        torch.manual_seed(7)

    def test_encoding_stable_and_numeric(self):
        a = encode([('["goods" "wood"]', 3), ('["space" "wood"]', 1)], ACTION_DIM)
        b = encode([('["space" "wood"]', 1), ('["goods" "wood"]', 3)], ACTION_DIM)
        np.testing.assert_array_equal(a, b)
        self.assertEqual(a.dtype, np.float32)
        self.assertTrue(np.isfinite(a).all())
        self.assertFalse(np.array_equal(a, encode([('["goods" "wood"]', 4)], ACTION_DIM)))

    def test_vector_backup_does_not_flip_on_same_player_turn(self):
        root = Node(torch.zeros(96), 0, np.zeros((1, ACTION_DIM)), np.ones(1))
        child = Node(torch.zeros(96), 0, np.zeros((1, ACTION_DIM)), np.ones(1))
        backup([(root, 0, np.array([.1, 0, 0, 0])), (child, 0, np.zeros(4))], np.array([.5, -.3, 0, 0]))
        np.testing.assert_allclose(root.totals[0], [.6, -.3, 0, 0])
        self.assertEqual(root.visits[0], 1)

    def test_search_returns_only_root_legal_actions(self):
        net = Network()
        legal = np.eye(ACTION_DIM, dtype=np.float32)[:3]
        result = search(net, np.zeros(OBS_DIM, np.float32), legal, 0, 2, legal,
                        np.random.default_rng(4), simulations=8)
        self.assertEqual(result.shape, (3,))
        self.assertAlmostEqual(float(result.sum()), 1.)
        self.assertTrue((result >= 0).all())

    def test_unrolled_training_updates_representation_dynamics_and_prediction(self):
        net, replay = Network(), Replay()
        actions = np.eye(ACTION_DIM, dtype=np.float32)[:2]
        replay.catalog = {'a': actions[0], 'b': actions[1]}
        replay.add([{"observation": np.ones(OBS_DIM, np.float32), "actions": actions,
                     "action": actions[0], "policy": np.array([1, 0], np.float32), "reward": np.zeros(SEATS, np.float32),
                     "actor": 0, "players": 2, "terminal": False, "value": np.array([.5, -.2, 0, 0], np.float32)},
                    {"observation": np.ones(OBS_DIM, np.float32), "actions": actions,
                     "action": actions[1], "policy": np.array([0, 1], np.float32), "reward": np.array([.5, -.2, 0, 0], np.float32),
                     "actor": 1, "players": 2, "terminal": True, "value": np.array([.5, -.2, 0, 0], np.float32)}])
        before = {k: v.detach().clone() for k, v in net.named_parameters()}
        loss = train_batch(net, torch.optim.AdamW(net.parameters(), lr=.001), replay, np.random.default_rng(2), batch_size=4)
        self.assertTrue(all(np.isfinite(list(loss.values()))))
        for key in ['representation.0.weight', 'dynamics.0.weight', 'query.weight', 'reward.weight', 'value.weight']:
            self.assertFalse(torch.equal(before[key], dict(net.named_parameters())[key]), key)


if __name__ == '__main__':
    unittest.main()
