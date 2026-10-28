"""Measure the serial CPU reference and batched CPU/CUDA learner on saved games."""
import argparse
import json
from pathlib import Path
import time
import numpy as np
import torch
from muzero import Network, Replay, train_batch, train_batch_serial


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--updates", type=int, default=20)
    args = parser.parse_args()
    torch.set_num_threads(1)
    saved = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    measurements = []
    for label, learner, device, batch in [("serial-cpu", train_batch_serial, "cpu", 12),
                                           ("batched-cpu", train_batch, "cpu", 12),
                                           ("batched-cuda", train_batch, "cuda", 12),
                                           ("batched-cpu", train_batch, "cpu", 48),
                                           ("batched-cuda", train_batch, "cuda", 48)]:
        if device == "cuda" and not torch.cuda.is_available():
            continue
        net = Network(saved["hidden"]).to(device)
        net.load_state_dict(saved["model"])
        optimizer = torch.optim.AdamW(net.parameters(), lr=.0003)
        rng = np.random.default_rng(777)
        for _ in range(3):
            learner(net, optimizer, saved["replay"], rng, batch_size=batch)
        if device == "cuda":
            torch.cuda.synchronize()
            torch.cuda.reset_peak_memory_stats()
        started = time.perf_counter()
        for _ in range(args.updates):
            loss = learner(net, optimizer, saved["replay"], rng, batch_size=batch)
        if device == "cuda":
            torch.cuda.synchronize()
        seconds = time.perf_counter() - started
        result = {"learner": label, "batch_size": batch, "updates": args.updates,
                  "seconds_per_update": seconds / args.updates,
                  "sequences_per_second": args.updates * batch / seconds, "last_loss": loss}
        if device == "cuda":
            result["peak_gpu_allocated_mb"] = torch.cuda.max_memory_allocated() / 1024**2
        measurements.append(result)
        print(json.dumps(result), flush=True)
        del optimizer, net
    report = {"torch": torch.__version__, "cuda": torch.version.cuda,
              "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
              "measurements": measurements}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
