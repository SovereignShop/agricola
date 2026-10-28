import copy
import unittest
import numpy as np
import torch
from encoding import OBS_DIM, ACTION_DIM, SEATS
from muzero import Network, Replay, train_batch_serial
from training import train_batch


def experience():
    rng = np.random.default_rng(91)
    replay = Replay()
    catalog = rng.normal(size=(7, ACTION_DIM)).astype(np.float32)
    replay.catalog = {str(i): row for i, row in enumerate(catalog)}
    for length, players in [(1, 1), (3, 2), (5, 4)]:
        game = []
        for i in range(length):
            actions = catalog[:(i % 3) + 2]
            selected = i % len(actions)
            game.append({"observation": rng.normal(size=OBS_DIM).astype(np.float32),
                         "actions": actions, "action": actions[selected],
                         "policy": np.eye(len(actions), dtype=np.float32)[selected],
                         "reward": rng.normal(size=SEATS).astype(np.float32) * .1,
                         "actor": i % players, "players": players,
                         "terminal": i == length - 1,
                         "value": rng.normal(size=SEATS).astype(np.float32)})
        replay.add(game)
    return replay


class BatchedTrainingTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        torch.manual_seed(11)

    def compare(self, device):
        reference = Network()
        batched = copy.deepcopy(reference).to(device)
        replay = experience()
        # SGD exposes gradient differences without Adam's first-step normalization.
        old = train_batch_serial(reference, torch.optim.SGD(reference.parameters(), lr=.01), replay,
                                 np.random.default_rng(42), batch_size=12, unroll=3)
        new = train_batch(batched, torch.optim.SGD(batched.parameters(), lr=.01), replay,
                          np.random.default_rng(42), batch_size=12, unroll=3)
        for name in old:
            self.assertAlmostEqual(old[name], new[name], places=5, msg=name)
        for name, value in reference.state_dict().items():
            torch.testing.assert_close(value, batched.state_dict()[name].cpu(), rtol=2e-4, atol=2e-6, msg=name)

    def test_cpu_matches_serial_with_ragged_actions_and_terminal_unrolls(self):
        self.compare("cpu")

    @unittest.skipUnless(torch.cuda.is_available(), "CUDA not installed")
    def test_cuda_matches_serial_loss_and_gradients(self):
        self.compare("cuda")

    def test_batched_policy_matches_individual_predictions(self):
        net = Network()
        hidden = torch.randn(4, net.hidden)
        actions = torch.randn(4, 7, ACTION_DIM)
        for method in [net.policy, net.legality]:
            torch.testing.assert_close(method(hidden, actions), torch.stack([method(h, a) for h, a in zip(hidden, actions)]))


if __name__ == "__main__":
    unittest.main()
