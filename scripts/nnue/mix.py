#!/usr/bin/env python3
"""Builds a training mix of packed positions (32-byte records, core.tools.PackData / DataGen *.bin).

Sources are files or slices of files (path[:start:end] in records). Positions with at most --endgame-pieces pieces
from the sources marked with '+' are appended once more (endgames counted twice: the real games the bot plays
have far more endgame positions than self-play from the start position produces).

Usage:
  python scripts/nnue/mix.py --out E:/Chess-Data/packed/gen3mix.bin \\
      E:/Chess-Data/packed/gen1mix.bin:0:2200000 +E:/Chess-Data/packed/gen02.bin +E:/Chess-Data/bigdata/sp-g2-a.bin
"""
import argparse
from pathlib import Path

import numpy as np

RECORD = 32


def read(spec: str) -> np.ndarray:
    parts = spec.rsplit(":", 2)
    # a slice is "path:start:end" with two numbers at the end (paths may contain "E:")
    path, rng = (parts[0], parts[1:]) if len(parts) == 3 and parts[1].isdigit() and parts[2].isdigit() else (spec, [])
    count = Path(path).stat().st_size // RECORD
    data = np.memmap(path, dtype=np.uint8, mode="r", shape=(count, RECORD))
    if rng:
        start, end = int(rng[0]), int(rng[1])
        data = data[start:min(end, count)]
    return data


def pieces(records: np.ndarray) -> np.ndarray:
    """Number of pieces per record (popcount of the occupancy bitboard)."""
    occ = records[:, :8]
    return np.unpackbits(occ, axis=1).sum(axis=1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sources", nargs="+", help="path or path:start:end; prefix + to also count its endgames twice")
    ap.add_argument("--out", required=True)
    ap.add_argument("--endgame-pieces", type=int, default=10)
    args = ap.parse_args()
    total = 0
    with open(args.out, "wb") as out:
        extras = []
        for spec in args.sources:
            twice = spec.startswith("+")
            data = read(spec[1:] if twice else spec)
            for lo in range(0, len(data), 10_000_000):
                part = np.asarray(data[lo:lo + 10_000_000])
                out.write(part.tobytes())
                total += len(part)
                if twice:
                    extras.append(part[pieces(part) <= args.endgame_pieces].copy())
            print(f"{spec}: {len(data)} positions", flush=True)
        n_extra = 0
        for e in extras:
            out.write(e.tobytes())
            n_extra += len(e)
        total += n_extra
    print(f"wrote {total} positions ({n_extra} endgame positions counted twice) to {args.out}")


if __name__ == "__main__":
    main()
