#!/usr/bin/env python3
"""Checks that the engine evaluates an exported network like the trainer: float model (train_gpu.py, .pt) vs the
Java engine's quantised evaluation ("eval" command with -Dcore.nnueFile) on positions from packed data.
Differences of a few centipawns are quantisation; anything larger means the two disagree about features.
A version 4 network's uncertainty output is compared too (the engine's "nnue uncertainty" line).

Usage: python scripts/nnue/parity.py --net E:/Chess-Data/nets/kb256.bin --data E:/Chess-Data/packed/sample.bin \\
           --classes <compiled core classes> [--count 200]
"""
import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import train_gpu as tg  # noqa: E402

PIECE_CHARS = "PNBRQKpnbrqk"


def fen_of(record: np.ndarray) -> str:
    occ = int.from_bytes(record[:8].tobytes(), "little")
    nib = record[8:24]
    pieces = [(int(b) & 15, int(b) >> 4) for b in nib]
    codes = [c for pair in pieces for c in pair]
    board = ["."] * 64
    i = 0
    for sq in range(64):
        if occ >> sq & 1:
            board[sq] = PIECE_CHARS[codes[i]]
            i += 1
    rows = []
    for rank in range(7, -1, -1):
        row, empty = "", 0
        for file in range(8):
            c = board[rank * 8 + file]
            if c == ".":
                empty += 1
            else:
                row += (str(empty) if empty else "") + c
                empty = 0
        rows.append(row + (str(empty) if empty else ""))
    return "/".join(rows) + (" w" if record[27] == 0 else " b") + " - - 0 1"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--net", required=True, help="exported .bin (the .pt next to it holds the float model)")
    ap.add_argument("--data", required=True)
    ap.add_argument("--classes", required=True)
    ap.add_argument("--count", type=int, default=200)
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--buckets", type=int, default=8)
    args = ap.parse_args()

    header = Path(args.net).read_bytes()[:104]
    version = int.from_bytes(header[4:8], "little")
    if version >= 3:  # the layout the file was trained with
        kb, mirror = int.from_bytes(header[28:32], "little"), int.from_bytes(header[32:36], "little")
        tg.configure((list(header[36:100]), bool(mirror), kb > 1))
    data = np.fromfile(args.data, dtype=np.uint8).reshape(-1, tg.RECORD)
    sample = data[np.random.default_rng(3).choice(len(data), size=min(args.count, len(data)), replace=False)]
    net = tg.Net(args.hidden, args.buckets, unc=version >= 4)
    net.load_state_dict(torch.load(Path(args.net).with_suffix(".pt"), map_location="cpu"))
    net.eval()
    kb = torch.tensor(tg.KING_BUCKETS)
    with torch.no_grad():
        out, unc = net.both(tg.Batch(sample, torch.device("cpu"), kb))
        floats = (out * tg.SCALE).numpy()
    fens = [fen_of(r) for r in sample]
    script = "".join(f"position fen {f}\neval\n" for f in fens) + "quit\n"
    out = subprocess.run(["java", "--add-modules", "jdk.incubator.vector", f"-Dcore.nnueFile={args.net}",
                          "-cp", args.classes, "julius.game.chessengine.core.Uci"],
                         input=script, capture_output=True, text=True).stdout
    java = [int(line.split()[5]) for line in out.splitlines() if line.startswith("nnue eval")]
    if len(java) != len(fens):
        raise SystemExit(f"engine answered {len(java)} of {len(fens)} evals:\n{out[-2000:]}")
    diff = np.abs(np.array(java) - floats)
    print(f"{len(fens)} positions: mean |java - float| {diff.mean():.2f} cp, max {diff.max():.1f} cp "
          f"(evals range {floats.min():.0f}..{floats.max():.0f})")
    worst = int(diff.argmax())
    print(f"worst: {fens[worst]} java {java[worst]} float {floats[worst]:.1f}")
    if diff.mean() > 5 or diff.max() > 40:  # quantisation: ~2-4 cp mean, up to ~20 on large evals
        raise SystemExit("PARITY FAILED")
    if unc is not None:
        java_u = [float(line.split()[2]) for line in out.splitlines() if line.startswith("nnue uncertainty")]
        if len(java_u) != len(fens):
            raise SystemExit(f"engine answered {len(java_u)} of {len(fens)} uncertainties")
        # the engine reports max(0, u) in thousandths
        du = np.abs(np.array(java_u) - np.maximum(unc.numpy(), 0)) * 1000
        print(f"uncertainty: mean |java - float| {du.mean():.2f}, max {du.max():.1f} thousandths "
              f"(range {unc.min().item():.3f}..{unc.max().item():.3f})")
        if du.mean() > 6 or du.max() > 35:  # quantisation as for the evaluation (5 cp / 400 = 12 thousandths)
            raise SystemExit("UNCERTAINTY PARITY FAILED")
    print("parity ok")


if __name__ == "__main__":
    main()
