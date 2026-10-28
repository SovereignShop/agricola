"""Long card-game experiment with retained demonstrations and held-out selection.

Uses the existing engine, encoder, network and MuZero loss. All real games are
EDN-recorded and replay-verified. Validation selects checkpoints; separate test
seeds are used only after training. Run in a detached process for long jobs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import traceback

import numpy as np
import torch
from edn_format import loads, Keyword

from encoding import ACTION_SPACE, VERSION
# Import Replay into __main__ for compatibility with original checkpoints.
from muzero import Environment, Network, Replay, play, reencode, save_checkpoint, train_batch
from enhancements import VERSION as ENHANCEMENT_VERSION, make_network, transfer_base
from draft_prior import DraftPrior, GuidedEnvironment
from rewards import VERSION as REWARD_VERSION


def atomic_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2))
    temporary.replace(path)


def match_summary(results):
    scores = [r["scores"][r["model_seat"]] for r in results]
    opponents = [r["scores"][1 - r["model_seat"]] for r in results]
    return {"games": len(scores), "mean_score": float(np.mean(scores)),
            "mean_opponent": float(np.mean(opponents)),
            "mean_margin": float(np.mean(np.subtract(scores, opponents))),
            "wins": sum(a > b for a, b in zip(scores, opponents)),
            "draws": sum(a == b for a, b in zip(scores, opponents)),
            "scores": scores,
            "mean_begging":float(np.mean([r['behavior']['begging'][r['model_seat']] for r in results])) if all('behavior' in r for r in results) else None,
            "mean_first_growth_round":float(np.mean([(r['behavior']['growth_rounds'][r['model_seat']] or [15])[0] for r in results])) if all('behavior' in r for r in results) else None}


def paired_summary(before, after):
    """Pair by identical deal and seat, bootstrapping deals (not correlated seats)."""
    old = {(r["seed"], r["model_seat"]): r["scores"][r["model_seat"]] for r in before}
    changes = {}
    for result in after:
        key = (result["seed"], result["model_seat"])
        changes.setdefault(key[0], []).append(result["scores"][key[1]] - old[key])
    differences = np.array([np.mean(v) for v in changes.values()])
    rng = np.random.default_rng(4521)
    means = rng.choice(differences, (10000, len(differences)), replace=True).mean(axis=1)
    return {"mean_score_improvement": float(differences.mean()),
            "deal_bootstrap_95_percent_interval": np.quantile(means, [.025, .975]).tolist(),
            "independent_deals": len(differences)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resume", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=21000)
    parser.add_argument("--games", type=int, default=384)
    parser.add_argument("--warmup-games", type=int, default=16)
    parser.add_argument("--pretrain-updates", type=int, default=1536)
    parser.add_argument("--updates-per-game", type=int, default=32)
    parser.add_argument("--simulations", type=int, default=32)
    parser.add_argument("--validation-deals", type=int, default=4)
    parser.add_argument("--validation-every", type=int, default=32)
    parser.add_argument("--test-deals", type=int, default=16)
    parser.add_argument("--batch-size", type=int, default=12)
    parser.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    parser.add_argument("--learning-rate", type=float, default=.0003)
    parser.add_argument("--transfer", action="store_true", help="Transfer weights only; discard stale optimizer and replay")
    parser.add_argument("--bootstrap-recordings", nargs="*", type=Path, default=[])
    parser.add_argument("--enhanced", action="store_true")
    parser.add_argument("--choice-weight", type=float, default=.5)
    parser.add_argument("--consistency-weight", type=float, default=.1)
    parser.add_argument("--card-sample-fraction", type=float, default=.35)
    parser.add_argument("--root-value-weight", type=float, default=.5)
    parser.add_argument("--ablate-root-value", action="store_true", help="Also evaluate the selected model without its choice-value search hint")
    parser.add_argument("--validation-seed", type=int, default=810100)
    parser.add_argument("--test-seed", type=int, default=910100)
    parser.add_argument("--draft-fraction", type=float, default=0., help="Fraction of training deals using the draw")
    parser.add_argument("--draft-prior", type=Path, help="Completed card study directory, for training-only draft guidance")
    parser.add_argument("--demonstration-fraction", type=float, default=.25)
    parser.add_argument("--demonstration-capacity", type=int, default=96)
    parser.add_argument("--curriculum", action="store_true", help="25% expert, 25% mixed opponents, 50% self-play")
    parser.add_argument("--train-seconds", type=float, help="Stop training at a game boundary, then run final evaluation")
    parser.add_argument("--reward-shaping",choices=['none',REWARD_VERSION],default='none')
    args = parser.parse_args()
    if min(args.validation_deals, args.test_deals, args.validation_every, args.simulations) < 1:
        parser.error("Evaluation counts, intervals and simulations must be positive")
    if min(args.games, args.warmup_games, args.pretrain_updates, args.updates_per_game) < 0 or args.batch_size < 1:
        parser.error("Training counts must be nonnegative and batch size positive")
    if not 0 <= args.card_sample_fraction <= 1 or min(args.choice_weight, args.consistency_weight, args.root_value_weight) < 0:
        parser.error("Invalid auxiliary loss or sampling weights")
    if not 0 <= args.draft_fraction <= 1 or not 0 <= args.demonstration_fraction <= 1 or args.demonstration_capacity<1 or (args.train_seconds is not None and args.train_seconds<=0):
        parser.error("Invalid curriculum or time budget")
    train_seeds = set(range(args.seed, args.seed + args.warmup_games)) | set(range(args.seed + 1000, args.seed + 1000 + args.games))
    validation_seeds = set(range(args.validation_seed, args.validation_seed + args.validation_deals))
    test_seeds = set(range(args.test_seed, args.test_seed + args.test_deals))
    if train_seeds & (validation_seeds | test_seeds) or validation_seeds & test_seeds:
        parser.error("Training, validation and test deal seeds must be disjoint")
    device = "cuda" if args.device == "auto" and torch.cuda.is_available() else ("cpu" if args.device == "auto" else args.device)
    if device == "cuda" and not torch.cuda.is_available():
        parser.error("CUDA was requested but is unavailable")
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "recordings").mkdir()
    torch.set_num_threads(1)
    rng = np.random.default_rng(args.seed)
    torch.manual_seed(args.seed)
    saved = torch.load(args.resume, map_location="cpu", weights_only=False)
    if saved['config'].get('inference_mode', 'search') != 'search' and not args.transfer:
        raise ValueError('Distilled policies require explicit --transfer before recurrent training')
    if (saved["schema"], saved["encoder"]) != (ACTION_SPACE, VERSION):
        raise ValueError("Checkpoint schema/encoder mismatch")
    if (saved["config"]["mode"], saved["config"]["players"]) != ("random", 2):
        raise ValueError("This experiment is for the two-player random-hand card game")
    settings = vars(args)
    if not args.transfer and args.enhanced != saved["config"].get("enhanced", False):
        raise ValueError("Changing network architecture requires explicit --transfer")
    if not args.transfer and args.reward_shaping!=saved['config'].get('reward_shaping','none'):
        raise ValueError('Changing rewards requires --transfer and freshly reconstructed replay targets')
    net = make_network(saved["hidden"], settings).to(device)
    initialized = transfer_base(net, saved["model"]) if args.transfer else []
    if not args.transfer:
        net.load_state_dict(saved["model"])
    net.eval()
    inference = make_network(saved["hidden"], settings).eval()
    original = make_network(saved["hidden"], saved["config"])
    original.load_state_dict(saved["model"])
    original.eval()
    optimizer = torch.optim.AdamW(net.parameters(), lr=args.learning_rate, weight_decay=1e-4)
    if not args.transfer:
        optimizer.load_state_dict(saved["optimizer"])
    for group in optimizer.param_groups:
        group["lr"] = args.learning_rate
    replay, updates = (Replay(), 0) if args.transfer else (saved["replay"], saved["updates"])
    replay.capacity = 160
    demonstrations = Replay(capacity=args.demonstration_capacity)
    demonstrations.catalog = replay.catalog
    config = {k: [str(x) for x in v] if isinstance(v, list) else str(v) if isinstance(v, Path) else v for k, v in vars(args).items()}
    config.update(mode="random", players=2, schema=ACTION_SPACE, encoder=VERSION,
                  device=device, search_device="cpu", torch=torch.__version__,
                  gpu=torch.cuda.get_device_name(0) if device == "cuda" else None,
                  learner_sha256=hashlib.sha256(Path(__file__).with_name("training.py").read_bytes()).hexdigest(), demonstration_batch_fraction=args.demonstration_fraction,
                  training_modes=["random", "draw"] if args.draft_fraction else ["random"],
                  enhancement_version=ENHANCEMENT_VERSION if args.enhanced else None,
                  parent_updates=saved["updates"], initialized_parameters=initialized,
                  transfer_source_sha256=hashlib.sha256(args.resume.read_bytes()).hexdigest(),
                  enhancements_sha256=hashlib.sha256(Path(__file__).with_name("enhancements.py").read_bytes()).hexdigest(),
                  search_sha256=hashlib.sha256(Path(__file__).with_name("muzero.py").read_bytes()).hexdigest(),
                  selection="validation score against fixed heuristic, both seats; weighted random/draw means by training mix",
                  trainer_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    draft_prior=DraftPrior(args.draft_prior) if args.draft_prior else None
    config["draft_prior_sha256"]=draft_prior.fingerprint if draft_prior else None
    config["confirmed_draft_combos"]=len(draft_prior.combos) if draft_prior else 0
    env = GuidedEnvironment(draft_prior,args.reward_shaping)
    started = time.monotonic()
    metrics = (args.output / "metrics.jsonl").open("w")
    report = {"config": config, "status": "starting", "updates": updates,
              "training_games": 0, "verified_games": 0, "verified_moves": 0,
              "validation": [], "evaluation": {}}

    def emit(stage, **values):
        event = dict(stage=stage, elapsed_seconds=round(time.monotonic() - started, 1), **values)
        metrics.write(json.dumps(event) + "\n")
        metrics.flush()
        print(json.dumps(event), flush=True)
        report.update(status=stage, updates=updates, elapsed_seconds=event["elapsed_seconds"])
        atomic_json(args.output / "status.json", report)

    def game(stage, index, seed, policy, model=net, training=False, catalog=None, mode="random"):
        # Small serial tree searches stay on CPU; optimizer updates use batched
        # CUDA kernels. Copy weights once per real game, not once per tree node.
        if model is net:
            inference.load_state_dict(net.state_dict())
            inference.eval()
            model = inference
        experience = replay if training else Replay()
        if not training:
            experience.catalog = dict(replay.catalog if catalog is None else catalog)
        env.guidance_enabled=training
        trajectory, result = play(env, model, experience, seed=seed, mode=mode, players=2,
                                  policy=policy, simulations=args.simulations,
                                  rng=rng if training else np.random.default_rng(seed + 4321),
                                  record=(args.output / "recordings" / f"{stage}-{index:04d}.edn").resolve(),
                                  training=training)
        result["mode"]=mode
        report["verified_games"] += 1
        report["verified_moves"] += len(trajectory)
        emit(stage, **result)
        return trajectory, result

    def matches(stage, model, seed_base, deals, catalog=None, mode="random"):
        results = []
        for deal in range(deals):
            for seat in range(2):
                policies = ["heuristic", "heuristic"]
                policies[seat] = "muzero"
                _, result = game(stage, deal * 2 + seat, seed_base + deal, policies, model, catalog=catalog, mode=mode)
                result["model_seat"] = seat
                results.append(result)
        return {"summary": match_summary(results), "results": results}

    def checkpoint(name):
        # Preserve the dedicated demonstration buffer for future continuation.
        replay.demonstrations = demonstrations.games
        save_checkpoint(args.output / name, net, optimizer, replay, rng, config, updates)

    best_score = -float("inf")

    def validate(label):
        nonlocal best_score
        result = matches(f"validation-{label}", net, config["validation_seed"], args.validation_deals)
        result.update(label=label, updates=updates)
        if args.draft_fraction:
            result['draft']=matches(f"validation-draft-{label}",net,config['validation_seed'],args.validation_deals,mode='draw')
        report["validation"].append(result)
        score = result["summary"]["mean_score"]
        if args.draft_fraction:
            score=(1-args.draft_fraction)*score+args.draft_fraction*result['draft']['summary']['mean_score']
        # A raw-score checkpoint has not learned the new value/reward meaning.
        # Keep it as the benchmark, not a mislabeled shaped checkpoint candidate.
        eligible=not (label=='baseline' and args.reward_shaping!=saved['config'].get('reward_shaping','none'))
        improved = eligible and score > best_score
        if improved:
            best_score = score
            report["best"] = {"label": label, "updates": updates, **result["summary"], 'selection_score':score,
                              'draft_summary':result.get('draft',{}).get('summary')}
            checkpoint("best.pt")
        emit("validation-summary", label=label, improved=improved, selection_score=score, **result["summary"])

    def optimize(count, stage):
        nonlocal updates
        for i in range(count):
            source = demonstrations if demonstrations.games and rng.random() < args.demonstration_fraction else replay
            loss = train_batch(net, optimizer, source, rng, args.batch_size)
            updates += 1
            if (i + 1) % 128 == 0 or i + 1 == count:
                emit(stage, updates=updates, loss=loss)

    try:
        schema=env.request(op="schema")
        fingerprints = schema["fingerprints"]
        config['learning_fingerprint']=schema.get('learning',{}).get('fingerprint') if args.reward_shaping!='none' else None
        config['reward_shaper_sha256']=hashlib.sha256(Path(__file__).with_name('rewards.py').read_bytes()).hexdigest() if args.reward_shaping!='none' else None
        if args.reward_shaping!='none':
            if schema.get('learning',{}).get('version')!=args.reward_shaping:raise ValueError('Engine reward scheme mismatch')
            if not args.transfer and any(config[k]!=saved['config'].get(k) for k in ['learning_fingerprint','reward_shaper_sha256']):
                raise ValueError('Reward implementation changed; transfer weights and rebuild targets')
        fingerprints["encoder"] = hashlib.sha256(Path(__file__).with_name("encoding.py").read_bytes()).hexdigest()
        if fingerprints != saved["config"]["fingerprints"] and not args.transfer:
            raise ValueError("Rules/action/encoder changed; refusing stale targets")
        if args.transfer and any(fingerprints[key] != saved["config"]["fingerprints"][key] for key in ["schema", "encoder"]):
            raise ValueError("Weight transfer requires the same feature encoding and dimensions")
        config["fingerprints"] = fingerprints
        config["parent_fingerprints"] = saved["config"]["fingerprints"]
        atomic_json(args.output / "config.json", config)
        emit("started", prior_updates=updates)
        for i, source in enumerate(args.bootstrap_recordings):
            with source.open() as source_file:
                header = loads(source_file.readline())
            if header.get(Keyword("seed")) in validation_seeds | test_seeds:
                raise ValueError("A held-out deal cannot be used as a bootstrap demonstration")
            destination = args.output / "recordings" / f"bootstrap-{i:04d}.edn"
            trajectory, scores = reencode(env, replay, source, destination)
            if trajectory[0]["players"] != 2:
                raise ValueError("This run requires two-player bootstrap games")
            replay.add(trajectory)
            demonstrations.add(trajectory)
            report["verified_games"] += 1
            report["verified_moves"] += len(trajectory)
            emit("bootstrap", source=str(source), scores=scores, moves=len(trajectory), replay="complete")
        validate("baseline")
        # The previous run's expert warmups have explicit provenance in config.
        # Re-execute EDN to avoid inferring demonstrations from a high score.
        # Put freshly verified study examples last, so bounded retention keeps them.
        demonstrations.games=(getattr(replay, "demonstrations", [])+demonstrations.games)[-demonstrations.capacity:]
        if not demonstrations.games and not args.transfer:
            for i, source in enumerate(saved["config"].get("bootstrap_recordings", [])):
                source = Path(source)
                if source.stem.startswith("warmup-") and int(source.stem.split("-")[-1]) % 4:
                    target = args.output / "recordings" / f"demonstration-{i:04d}.edn"
                    trajectory, scores = reencode(env, replay, source, target)
                    demonstrations.add(trajectory)
                    report["verified_games"] += 1
                    report["verified_moves"] += len(trajectory)
                    emit("demonstration", scores=scores, moves=len(trajectory), replay="complete")
        for i in range(args.warmup_games):
            trajectory, _ = game("warmup", i, args.seed + i, "heuristic", training=True,
                                 mode="draw" if rng.random()<args.draft_fraction else "random")
            replay.add(trajectory)
            demonstrations.add(trajectory)
        optimize(args.pretrain_updates, "pretrain")
        checkpoint("latest.pt")
        validate("pretrain")
        for i in range(args.games):
            if args.train_seconds is not None and time.monotonic()-started>=args.train_seconds:
                emit("training-budget-finished", completed_games=i)
                break
            # Fresh expert examples and mixed opponents prevent a purely weak
            # self-play distribution from erasing useful early experience.
            if args.curriculum:
                policy = "heuristic" if i%8<2 else (["muzero","heuristic"] if i%8==2 else ["heuristic","muzero"]) if i%8<4 else "muzero"
            elif i % 8 == 0:
                policy = "heuristic"
            elif i % 4 == 0:
                policy = ["muzero", "heuristic"] if (i // 4) % 4 == 1 else ["heuristic", "muzero"]
            else:
                policy = "muzero"
            trajectory, _ = game("training", i, args.seed + 1000 + i, policy, training=True,
                                 mode="draw" if rng.random()<args.draft_fraction else "random")
            replay.add(trajectory)
            if policy == "heuristic":
                demonstrations.add(trajectory)
            report["training_games"] = i + 1
            optimize(args.updates_per_game, "optimization")
            if (i + 1) % 8 == 0:
                checkpoint("latest.pt")
            if (i + 1) % args.validation_every == 0 or i + 1 == args.games:
                validate(f"game-{i + 1:04d}")
        checkpoint("latest.pt")
        if report['validation'][-1]['updates']!=updates:
            validate('training-end')
        best = torch.load(args.output / "best.pt", map_location="cpu", weights_only=False)
        net.load_state_dict(best["model"])
        net.eval()
        best_catalog = best["replay"].catalog
        emit("final-evaluation", selected_updates=best["updates"])
        # Both models get the same training-only proposal catalog, isolating
        # learned weights rather than catalog coverage in the final comparison.
        for label, model, catalog in [("previous", original, best_catalog), ("best", net, best_catalog)]:
            report["evaluation"][label] = matches(f"test-{label}", model, config["test_seed"], args.test_deals, catalog)
            emit("test-summary", model=label, **report["evaluation"][label]["summary"])
        report["paired_comparison"] = paired_summary(report["evaluation"]["previous"]["results"],
                                                     report["evaluation"]["best"]["results"])
        if args.enhanced and args.ablate_root_value:
            no_hint = make_network(saved["hidden"], dict(config, root_value_weight=0.)).eval()
            no_hint.load_state_dict(best["model"])
            result = matches("test-no-value-hint", no_hint, config["test_seed"], args.test_deals, best_catalog)
            report["evaluation"]["no-value-hint"] = result
            report["root_value_comparison"] = paired_summary(result["results"], report["evaluation"]["best"]["results"])
            emit("value-hint-ablation", **result["summary"], comparison=report["root_value_comparison"])
        # Include same-policy self-play for comparison with the old -5.5 figure.
        for label, model, catalog in [("previous", original, best_catalog), ("best", net, best_catalog)]:
            results = [game(f"test-self-{label}", i, config["test_seed"] + i, "muzero", model, catalog=catalog)[1]
                       for i in range(args.test_deals)]
            report["evaluation"][f"self-{label}"] = {"mean_score": float(np.mean([r["scores"] for r in results])), "results": results}
        if args.draft_fraction:
            for label,model in [("previous",original),("best",net)]:
                report["evaluation"][f"draft-{label}"]=matches(f"test-draft-{label}",model,config["test_seed"],args.test_deals,best_catalog,mode="draw")
            report["draft_comparison"]=paired_summary(report["evaluation"]["draft-previous"]["results"],report["evaluation"]["draft-best"]["results"])
        emit("complete", comparison=report["paired_comparison"], best=report["best"])
        atomic_json(args.output / "report.json", report)
    except BaseException:
        emit("failed", error=traceback.format_exc())
        raise
    finally:
        metrics.close()
        env.close()


if __name__ == "__main__":
    main()
