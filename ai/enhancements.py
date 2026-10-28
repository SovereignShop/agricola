"""Contextual decision values and auxiliary learning for the next experiment.

The value is expected final score after a choice, not a causal card bonus.
Only public/own-hand observation and legal command features enter predictions.
"""
import torch
from torch import nn
from muzero import Network


VERSION = "contextual-choice-v1"


class CardValueNetwork(Network):
    def __init__(self, hidden, *, choice_weight=.5, consistency_weight=.1,
                 card_sample_fraction=.35, root_value_weight=.5):
        super().__init__(hidden)
        self.choice_head = nn.Sequential(nn.Linear(2 * hidden, hidden), nn.ReLU(), nn.Linear(hidden, 1))
        # Start with no change to root priors when transferring base weights.
        nn.init.zeros_(self.choice_head[-1].weight)
        nn.init.zeros_(self.choice_head[-1].bias)
        self.choice_weight = choice_weight
        self.consistency_weight = consistency_weight
        self.card_sample_fraction = card_sample_fraction
        self.root_value_weight = root_value_weight

    def choice_value(self, hidden, actions):
        encoded = self.action(actions)
        context = hidden.unsqueeze(-2).expand_as(encoded)
        return self.choice_head(torch.cat([context, encoded], dim=-1)).squeeze(-1)

    def root_policy(self, hidden, actions, actor):
        logits = self.policy(hidden, actions)
        values = self.choice_value(hidden, actions)
        # A bounded relative-score hint; search still verifies real root legality.
        advantage = (values - values.mean()).clamp(-1., 1.)
        return logits + self.root_value_weight * advantage


def make_network(hidden, config):
    mode = config.get('inference_mode', 'search')
    if mode not in ['search', 'policy', 'guarded-policy']:
        raise ValueError('Unknown inference mode')
    if not config.get("enhanced", False):
        model = Network(hidden)
        model.inference_mode = mode
        model.guidance_margin = config.get('guidance_margin', .5)
        model.guidance_fingerprint = config.get('guidance_fingerprint')
        return model
    if config.get("enhancement_version", VERSION) != VERSION:
        raise ValueError("Unknown enhancement version")
    model = CardValueNetwork(hidden, **{key: config[key] for key in
        ["choice_weight", "consistency_weight", "card_sample_fraction", "root_value_weight"] if key in config})
    model.inference_mode = mode
    model.guidance_margin = config.get('guidance_margin', .5)
    model.guidance_fingerprint = config.get('guidance_fingerprint')
    return model


def transfer_base(model, saved):
    """Load compatible tensors; never migrate optimizer/replay implicitly."""
    result = model.load_state_dict(saved, strict=False)
    allowed = {key for key in model.state_dict() if key.startswith("choice_head.")}
    if set(result.missing_keys) - allowed or result.unexpected_keys:
        raise ValueError(f"Incompatible transferred weights: {result}")
    return list(result.missing_keys)
