"""
Data generator v3: labels with both Stockfish and our classic evaluator.

For each sampled position we produce:
  - Stockfish eval at a fixed depth  (the "truth")
  - Our classic engine's static eval  (the NN's input prior — feature 70)

The training target becomes  stockfish - classic  (the residual the NN must predict).

Requires the engine JAR built with the UCI 'eval' command:
    java ... -Dchessengine.eval.mode=classic -jar .../chess-engine-*-uci.jar
"""

from __future__ import annotations

import argparse
import glob
import os
import random
import subprocess
import sys
import time
from pathlib import Path

import chess
import chess.engine
import chess.pgn
import numpy as np

import feature_extractor as fe

STOCKFISH_PATH = r"E:\ChessEngines\stockfish_17.1\stockfish-windows-x86-64-avx2.exe"
ENGINE_JAR = r"C:\Development\Chess-Engine\target\chess-engine-4.0.0-uci.jar"
OPENING_DIR = Path(r"C:\Development\Chess-Engine\src\main\resources\opening")
CLAMP_CP = 2000


def sample_from_pgns(pgn_paths, count, skip_plies_min=4, skip_plies_max=60):
    positions = []
    files = list(pgn_paths)
    random.shuffle(files)
    for path in files:
        if len(positions) >= count:
            break
        try:
            with open(path, errors="ignore") as fh:
                while len(positions) < count:
                    game = chess.pgn.read_game(fh)
                    if game is None:
                        break
                    board = game.board()
                    plies = 0
                    for move in game.mainline_moves():
                        board.push(move)
                        plies += 1
                        if plies < skip_plies_min:
                            continue
                        if plies > skip_plies_max:
                            break
                        if random.random() < 0.15 and not board.is_game_over():
                            positions.append(board.fen())
                            if len(positions) >= count:
                                break
        except Exception as e:
            print(f"[warn] PGN {path}: {e}", file=sys.stderr, flush=True)
    return positions


def sample_random_positions(count, max_plies=40):
    positions = []
    while len(positions) < count:
        board = chess.Board()
        depth = random.randint(4, max_plies)
        try:
            for _ in range(depth):
                moves = list(board.legal_moves)
                if not moves:
                    break
                board.push(random.choice(moves))
            if not board.is_game_over():
                positions.append(board.fen())
        except Exception:
            continue
    return positions


def label_with_stockfish(fens, depth, threads=4, hash_mb=256):
    labels = [None] * len(fens)
    engine = chess.engine.SimpleEngine.popen_uci(STOCKFISH_PATH)
    try:
        engine.configure({"Threads": threads, "Hash": hash_mb})
        start = time.time()
        for idx, fen in enumerate(fens):
            board = chess.Board(fen)
            if board.is_game_over():
                continue
            try:
                info = engine.analyse(board, chess.engine.Limit(depth=depth))
                sc = info["score"].white().score(mate_score=10000)
                if sc is not None:
                    labels[idx] = max(-CLAMP_CP, min(CLAMP_CP, sc))
            except Exception as e:
                print(f"[warn] SF analyse {idx}: {e}", file=sys.stderr, flush=True)
            if (idx + 1) % 1000 == 0:
                elapsed = time.time() - start
                rate = (idx + 1) / elapsed if elapsed > 0 else 0
                print(f"  stockfish {idx+1}/{len(fens)} ({rate:.0f}/s)", flush=True)
    finally:
        engine.quit()
    return labels


def label_with_classic(fens):
    """
    Start our engine in classic mode, loop over FENs, query the 'eval' command.
    This is fast because we just set the position and ask for the eval.
    """
    classic = [None] * len(fens)
    java_cmd = [
        "java", "--enable-preview", "--enable-native-access=ALL-UNNAMED",
        "-Xmx2g",
        "-Dchessengine.eval.mode=classic",
        "-Dchessengine.openingbook.enabled=false",
        "-jar", ENGINE_JAR,
    ]
    proc = subprocess.Popen(
        java_cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        bufsize=1,
    )
    try:
        # UCI handshake
        proc.stdin.write("uci\n")
        proc.stdin.flush()
        # Wait for uciok
        while True:
            line = proc.stdout.readline()
            if not line:
                raise RuntimeError("Engine closed during handshake")
            if line.strip() == "uciok":
                break
        proc.stdin.write("isready\n")
        proc.stdin.flush()
        while True:
            line = proc.stdout.readline()
            if line.strip() == "readyok":
                break

        start = time.time()
        for idx, fen in enumerate(fens):
            proc.stdin.write(f"position fen {fen}\n")
            proc.stdin.write("eval\n")
            proc.stdin.flush()
            # Read until we see the classiceval line
            eval_cp = None
            deadline = time.time() + 5
            while time.time() < deadline:
                line = proc.stdout.readline()
                if not line:
                    break
                line = line.strip()
                if "classiceval" in line:
                    try:
                        eval_cp = int(line.rsplit(" ", 1)[-1])
                    except ValueError:
                        eval_cp = 0
                    break
            classic[idx] = eval_cp if eval_cp is not None else 0
            if (idx + 1) % 1000 == 0:
                elapsed = time.time() - start
                rate = (idx + 1) / elapsed if elapsed > 0 else 0
                print(f"  classic {idx+1}/{len(fens)} ({rate:.0f}/s)", flush=True)
    finally:
        try:
            proc.stdin.write("quit\n")
            proc.stdin.flush()
        except Exception:
            pass
        proc.wait(timeout=5)
    return classic


def build_dataset(fens, sf_labels, classic_labels):
    rows = []
    for fen, sf, cl in zip(fens, sf_labels, classic_labels):
        if sf is None:
            continue
        # Clamp classic eval to the same range as Stockfish labels so residuals are bounded
        cl_clamped = max(-CLAMP_CP, min(CLAMP_CP, cl or 0))
        board = chess.Board(fen)
        feats = fe.extract_features(board, classic_score_cp=cl_clamped)
        rows.append((feats, sf, cl_clamped))
    if not rows:
        return np.zeros((0, fe.FEATURE_COUNT), dtype=np.float32), np.zeros(0, dtype=np.float32), np.zeros(0, dtype=np.float32)
    X = np.stack([r[0] for r in rows])
    y_sf = np.array([r[1] for r in rows], dtype=np.float32)
    y_classic = np.array([r[2] for r in rows], dtype=np.float32)
    return X, y_sf, y_classic


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--positions", type=int, default=20000)
    ap.add_argument("--depth", type=int, default=10)
    ap.add_argument("--pgn-ratio", type=float, default=0.05)
    ap.add_argument("--sf-threads", type=int, default=4)
    ap.add_argument("--sf-hash", type=int, default=256)
    ap.add_argument("--out", type=str, default="training_data_v3.npz")
    args = ap.parse_args()

    random.seed(42)

    print(f"Sampling {args.positions} positions...", flush=True)
    pgn_target = int(args.positions * args.pgn_ratio)
    random_target = args.positions - pgn_target
    pgn_files = glob.glob(str(OPENING_DIR / "*.pgn"))
    pgn_positions = sample_from_pgns(pgn_files, pgn_target)
    random_positions = sample_random_positions(random_target)
    all_positions = list(dict.fromkeys(pgn_positions + random_positions))[: args.positions]
    print(f"  Unique positions: {len(all_positions)}", flush=True)

    print(f"Stockfish labels (depth {args.depth})...", flush=True)
    sf_labels = label_with_stockfish(all_positions, args.depth,
                                      threads=args.sf_threads, hash_mb=args.sf_hash)
    valid = sum(1 for s in sf_labels if s is not None)
    print(f"  Valid stockfish labels: {valid}", flush=True)

    print("Classic labels from our engine...", flush=True)
    classic_labels = label_with_classic(all_positions)
    valid_cl = sum(1 for c in classic_labels if c is not None)
    print(f"  Valid classic labels: {valid_cl}", flush=True)

    print("Building feature vectors...", flush=True)
    X, y_sf, y_cl = build_dataset(all_positions, sf_labels, classic_labels)
    print(f"  Dataset: X={X.shape}, y_sf range=[{y_sf.min():.0f}, {y_sf.max():.0f}]", flush=True)
    print(f"  Classic range=[{y_cl.min():.0f}, {y_cl.max():.0f}], mean={y_cl.mean():.1f}", flush=True)
    # Residual target
    residual = y_sf - y_cl
    print(f"  Residual range=[{residual.min():.0f}, {residual.max():.0f}], "
          f"mean={residual.mean():.1f}, std={residual.std():.1f}", flush=True)

    np.savez_compressed(args.out, X=X, y_sf=y_sf, y_classic=y_cl, y_residual=residual)
    print(f"Saved {args.out} ({os.path.getsize(args.out)/1024/1024:.1f} MB)", flush=True)


if __name__ == "__main__":
    main()
