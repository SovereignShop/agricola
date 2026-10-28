import unittest
import numpy as np
import torch
from encoding import HIDDEN, OBS_DIM, ACTION_DIM, SEATS
from enhancements import CardValueNetwork, make_network, transfer_base
from muzero import Network, Replay, is_card_choice, search, guarded_policy
from training import sample_sequences, train_batch
from test_training import experience


class EnhancementTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        torch.manual_seed(17)

    def test_distilled_policy_never_uses_stale_dynamics_or_choice_values(self):
        model = make_network(HIDDEN, {'enhanced': True, 'inference_mode': 'policy'})
        def forbidden(*args, **kwargs):
            raise AssertionError('A distilled policy must not use stale search heads')
        model.recurrent = forbidden
        model.root_policy = forbidden
        observation = np.zeros(OBS_DIM, np.float32)
        legal = np.eye(ACTION_DIM, dtype=np.float32)[:3]
        with torch.no_grad():
            expected = torch.softmax(model.policy(model.representation(torch.from_numpy(observation)),
                                                  torch.from_numpy(legal)), -1).numpy()
        result = search(model, observation, legal, 0, 2, legal[::-1].copy(), np.random.default_rng(9), 32)
        np.testing.assert_allclose(result, expected)
        with self.assertRaises(ValueError):
            make_network(HIDDEN, {'inference_mode': 'unknown'})

    def test_hybrid_rejects_a_high_probability_bad_action_and_checks_provenance(self):
        model = make_network(HIDDEN, {'enhanced': True, 'inference_mode': 'guarded-policy',
                                      'guidance_margin': .5, 'guidance_fingerprint': 'fixture'})
        observation = np.zeros(OBS_DIM, np.float32)
        legal = np.eye(ACTION_DIM, dtype=np.float32)[:3]
        with torch.no_grad():
            logits = model.policy(model.representation(torch.from_numpy(observation)),torch.from_numpy(legal)).numpy()
        bad = int(logits.argmax())
        values = np.zeros(3); values[bad] = -10
        advice = {'fingerprint': 'fixture', 'values': values.tolist()}
        distribution = guarded_policy(model, observation, legal, advice)
        self.assertEqual(float(distribution[bad]), 0.)
        self.assertAlmostEqual(float(distribution.sum()), 1.)
        self.assertGreaterEqual(values[distribution.argmax()], values.max()-.5)
        with self.assertRaises(ValueError):
            guarded_policy(model, observation, legal, dict(advice,fingerprint='changed'))
        with self.assertRaises(ValueError):
            search(model,observation,legal,0,2,legal,np.random.default_rng(1),32)

    def test_transfer_preserves_base_and_starts_with_identical_root_policy(self):
        base = Network()
        model = CardValueNetwork(HIDDEN)
        missing = transfer_base(model, base.state_dict())
        self.assertTrue(missing)
        self.assertTrue(all(k.startswith("choice_head.") for k in missing))
        for key, value in base.state_dict().items():
            torch.testing.assert_close(model.state_dict()[key], value)
        h = base.representation(torch.ones(OBS_DIM))
        a = torch.eye(ACTION_DIM)[:3]
        torch.testing.assert_close(base.policy(h, a), model.root_policy(h, a, 0))
        bad = dict(base.state_dict(), unexpected=torch.zeros(1))
        with self.assertRaises(ValueError):
            transfer_base(model, bad)

    def test_card_detection_and_focused_sampling(self):
        for path in ["[:card :revised-base/a001]", "[:improvement :well]",
                     "[:action-command :card :revised-five/a169]", "[:card-space :revised-five/b171]"]:
            self.assertTrue(is_card_choice({"features": [[path, 1]]}), path)
        self.assertFalse(is_card_choice({"features": [["[:space :wood]", 1], ["[:discard false]", 1]]}))
        replay = experience()
        replay.games[1][1]["card_choice"] = True
        sequences = sample_sequences(replay, np.random.default_rng(21), 32, 0, card_fraction=1.)
        self.assertTrue(all(s[0][0].get("card_choice") for s in sequences))

    def test_choice_values_learn_actor_return_and_card_context(self):
        model, replay = CardValueNetwork(HIDDEN, card_sample_fraction=0.), Replay()
        actions = np.eye(ACTION_DIM, dtype=np.float32)[:2]
        replay.catalog = dict(enumerate(actions))
        obs = np.ones(OBS_DIM, np.float32)
        for choice, target in [(0, -.5), (1, .8)]:
            returns = np.zeros(SEATS, np.float32)
            returns[0], returns[1] = -target, target
            replay.add([dict(observation=obs, actions=actions, action=actions[choice],
                             policy=np.eye(2, dtype=np.float32)[choice], reward=returns,
                             value=returns, actor=1, players=2, terminal=True, card_choice=True)])
        optimizer = torch.optim.Adam(model.parameters(), lr=.003)
        rng = np.random.default_rng(2)
        first = train_batch(model, optimizer, replay, rng, batch_size=16, unroll=0)
        for _ in range(99):
            last = train_batch(model, optimizer, replay, rng, batch_size=16, unroll=0)
        self.assertLess(last["choice_value"], first["choice_value"] * .1)
        with torch.no_grad():
            values = model.choice_value(model.representation(torch.from_numpy(obs)), torch.from_numpy(actions))
        self.assertLess(float(values[0]), -.3)
        self.assertGreater(float(values[1]), .6)
        restored = make_network(HIDDEN, {"enhanced": True})
        restored.load_state_dict(model.state_dict())
        with torch.no_grad():
            torch.testing.assert_close(values, restored.choice_value(restored.representation(torch.from_numpy(obs)), torch.from_numpy(actions)))
        no_hint = make_network(HIDDEN, {"enhanced": True, "root_value_weight": 0.})
        no_hint.load_state_dict(model.state_dict())
        h, a = no_hint.representation(torch.from_numpy(obs)), torch.from_numpy(actions)
        torch.testing.assert_close(no_hint.root_policy(h, a, 1), no_hint.policy(h, a))

    def test_consistency_ignores_other_seats_private_observations(self):
        replay = experience()
        for game in replay.games:
            for i, step in enumerate(game):
                step["players"], step["actor"] = 2, i % 2
        model = CardValueNetwork(HIDDEN)
        optimizer = torch.optim.SGD(model.parameters(), lr=.01)
        loss = train_batch(model, optimizer, replay, np.random.default_rng(4), batch_size=32)
        self.assertEqual(loss["consistency"], 0.)
        for game in replay.games:
            for step in game:
                step["actor"] = 0
        loss = train_batch(model, optimizer, replay, np.random.default_rng(4), batch_size=32)
        self.assertGreater(loss["consistency"], 0.)

    @unittest.skipUnless(torch.cuda.is_available(), "CUDA not available")
    def test_enhanced_cuda_gradients_and_search_are_finite(self):
        model, replay = CardValueNetwork(HIDDEN).cuda(), experience()
        loss = train_batch(model, torch.optim.AdamW(model.parameters()), replay, np.random.default_rng(5), 12)
        self.assertTrue(all(np.isfinite(list(loss.values()))))
        model.cpu()
        actions = replay.pool()[:3]
        visits = search(model, np.zeros(OBS_DIM, np.float32), actions, 0, 2, replay.pool(), np.random.default_rng(5), 8)
        self.assertEqual(visits.shape, (3,))
        self.assertAlmostEqual(float(visits.sum()), 1.)


if __name__ == "__main__":
    unittest.main()
