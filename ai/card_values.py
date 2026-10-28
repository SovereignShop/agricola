"""Inspect contextual card/action values at a real, legal game position."""
import argparse
import hashlib
import json
from pathlib import Path
import torch
from edn_format import Keyword
from encoding import encode, OBS_DIM, ACTION_DIM, SCHEMA
from enhancements import make_network
from muzero import Environment, Replay, is_card_choice


@torch.no_grad()
def rank_choices(model, response, reward_shaping='none'):
    if response["terminal"]:
        return []
    hidden = model.representation(torch.from_numpy(encode(response["observation"], OBS_DIM)))
    actions = torch.stack([torch.from_numpy(encode(a["features"], ACTION_DIM)) for a in response["actions"]])
    values = model.choice_value(hidden, actions)
    scale = SCHEMA[Keyword("reward")][Keyword("scale")]
    correction=response['learning']['potentials'][response['player']] if reward_shaping!='none' else 0.
    rows = [dict(command=action["edn"], card_choice=is_card_choice(action),
                 estimated_final_score=round(float(value) * scale + correction, 2))
            for action, value in zip(response["actions"], values)]
    return sorted(rows, key=lambda row: row["estimated_final_score"], reverse=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--recording", type=Path, help="Inspect a verified recording instead of a new deal")
    parser.add_argument("--move", type=int, default=0)
    parser.add_argument("--seed", type=int, default=71000)
    parser.add_argument("--limit", type=int, default=10)
    args = parser.parse_args()
    if args.move < 0 or args.limit < 1 or (args.move and not args.recording):
        parser.error("Use a nonnegative recording move and a positive limit")
    torch.set_num_threads(1)
    saved = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    if saved['config'].get('training_algorithm') == 'expert-distillation-v1':
        parser.error('This policy-only checkpoint does not train its card-value head; use a value-trained checkpoint for estimates')
    model = make_network(saved["hidden"], saved["config"]).eval()
    if not hasattr(model, "choice_value"):
        parser.error("This checkpoint has no contextual card-value estimator")
    model.load_state_dict(saved["model"])
    env = Environment()
    try:
        schema=env.request(op='schema')
        fingerprints = schema["fingerprints"]
        if saved['config'].get('reward_shaping','none')!='none' and saved['config'].get('learning_fingerprint')!=schema.get('learning',{}).get('fingerprint'):
            raise ValueError('Checkpoint reward interpretation does not match the engine')
        fingerprints["encoder"] = hashlib.sha256(Path(__file__).with_name("encoding.py").read_bytes()).hexdigest()
        if fingerprints != saved["config"]["fingerprints"]:
            raise ValueError("Checkpoint rules/encoding do not match the current engine")
        if args.recording:
            response = env.request(op="load-recording", path=str(args.recording.resolve()))
            for _ in range(args.move):
                if response["terminal"] or response["recorded-action"] is None:
                    raise ValueError("Requested move is beyond the recording or outside the macro action space")
                response = env.request(op="step", action=response["recorded-action"])
        else:
            response = env.request(op="reset", seed=args.seed, players=saved["config"]["players"], mode=saved["config"]["mode"])
        rows = rank_choices(model, response,saved['config'].get('reward_shaping','none'))
        print(json.dumps(dict(round=response["round"], player=response["player"], move=response["ply"],
                              interpretation="Expected final score after this legal choice; not a causal card bonus or a calibrated guarantee.",
                              cards=[r for r in rows if r["card_choice"]][:args.limit],
                              alternatives=[r for r in rows if not r["card_choice"]][:args.limit]), indent=2))
    finally:
        env.close()


if __name__ == "__main__":
    main()
