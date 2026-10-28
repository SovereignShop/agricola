"""Play with a trained checkpoint and write verified EDN games for the viewer."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import torch
from encoding import VERSION, ACTION_SPACE
# Replay is also imported into __main__ for checkpoints written by muzero.py.
from muzero import Environment, Network, Replay, play
from enhancements import make_network


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=900000)
    parser.add_argument("--simulations", type=int, default=32)
    parser.add_argument("--opponent", choices=["self", "random", "heuristic"], default="self")
    args = parser.parse_args()
    torch.set_num_threads(2)
    saved = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    if saved["schema"] != ACTION_SPACE or saved["encoder"] != VERSION:
        raise ValueError("Checkpoint encoder/action schema mismatch")
    model = make_network(saved["hidden"], saved["config"])
    model.load_state_dict(saved["model"])
    model.eval()
    replay = Replay()
    replay.catalog = dict(saved["replay"].catalog)
    env = Environment()
    try:
        fingerprints = env.request(op="schema")["fingerprints"]
        fingerprints["encoder"] = hashlib.sha256((Path(__file__).parent / "encoding.py").read_bytes()).hexdigest()
        if fingerprints != saved["config"]["fingerprints"]:
            raise ValueError("Rules or encoding changed; revalidate/retrain before using this checkpoint")
        players = saved["config"]["players"]
        policy = "muzero" if args.opponent == "self" else ["muzero"] + [args.opponent] * (players - 1)
        _, result = play(env, model, replay, seed=args.seed, mode=saved["config"]["mode"], players=players,
                         policy=policy, simulations=args.simulations, rng=np.random.default_rng(args.seed), record=args.output)
        print(json.dumps(result, indent=2))
    finally:
        env.close()


if __name__ == "__main__":
    main()
