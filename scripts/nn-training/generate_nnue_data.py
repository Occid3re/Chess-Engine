#!/usr/bin/env python3
"""
Generate NNUE training data from PGN databases + Stockfish labels.

Output: positions.npz with:
  - fens: array of FEN strings
  - evals: int16 array of Stockfish centipawn evals (clamped +-2000)
  - wdl: uint8 array of game outcome (2=white win, 1=draw, 0=black win)

Usage:
  python generate_nnue_data.py --pgn-dir E:/Engine-Pgns --sf-path E:/ChessEngines/stockfish_17.1/stockfish-windows-x86-64-avx2.exe --positions 100000 --depth 16 --out nnue_data.npz
"""
import argparse
import chess
import chess.pgn
import chess.engine
import glob
import io
import numpy as np
import os
import random
import sys
import time
from pathlib import Path


def parse_result(result_str):
    """Convert PGN result to WDL: 2=white win, 1=draw, 0=black win."""
    if result_str == "1-0":
        return 2
    elif result_str == "0-1":
        return 0
    elif result_str == "1/2-1/2":
        return 1
    return -1  # unknown


def sample_positions_from_pgn(pgn_dir, target_count, min_ply=12, max_ply=200, sample_rate=0.15):
    """Sample diverse positions from PGN files."""
    pgn_files = sorted(glob.glob(os.path.join(pgn_dir, "*.pgn")))
    if not pgn_files:
        print(f"[!] No PGN files found in {pgn_dir}")
        return []

    print(f"[+] Found {len(pgn_files)} PGN files")
    positions = []  # list of (fen, wdl)
    games_read = 0

    for pgn_path in pgn_files:
        print(f"  Reading {os.path.basename(pgn_path)}...")
        with open(pgn_path, encoding="utf-8", errors="replace") as f:
            while len(positions) < target_count:
                try:
                    game = chess.pgn.read_game(f)
                except Exception:
                    continue
                if game is None:
                    break

                games_read += 1
                result = game.headers.get("Result", "*")
                wdl = parse_result(result)
                if wdl == -1:
                    continue

                # Walk through the game, sampling positions
                board = game.board()
                ply = 0
                for move in game.mainline_moves():
                    # Check capture BEFORE pushing (is_capture inspects current board)
                    is_capture = board.is_capture(move)
                    board.push(move)
                    ply += 1
                    if ply < min_ply or ply > max_ply:
                        continue
                    # Skip positions in check (noisy evals)
                    if board.is_check():
                        continue
                    # Skip captures (noisy, Stockfish eval fluctuates)
                    if is_capture:
                        continue
                    if random.random() < sample_rate:
                        positions.append((board.fen(), wdl))

                if games_read % 1000 == 0:
                    print(f"    {games_read} games, {len(positions)} positions sampled", end="\r")

        if len(positions) >= target_count:
            break

    print(f"\n[+] Sampled {len(positions)} positions from {games_read} games")
    random.shuffle(positions)
    return positions[:target_count]


def _label_chunk(args):
    """Worker function: label a chunk of positions with one Stockfish instance."""
    chunk, sf_path, depth, threads_per_worker, hash_per_worker, worker_id = args

    engine = chess.engine.SimpleEngine.popen_uci(sf_path)
    engine.configure({"Threads": threads_per_worker, "Hash": hash_per_worker})

    fens = []
    evals = []
    wdls = []
    failed = 0

    for i, (fen, wdl) in enumerate(chunk):
        try:
            board = chess.Board(fen)
            info = engine.analyse(board, chess.engine.Limit(depth=depth))
            score = info["score"].white()

            if score.is_mate():
                mate_moves = score.mate()
                cp = 10000 if mate_moves > 0 else -10000
            else:
                cp = score.score()
                if cp is None:
                    failed += 1
                    continue

            cp = max(-2000, min(2000, cp))
            fens.append(fen)
            evals.append(cp)
            wdls.append(wdl)

        except Exception as e:
            failed += 1
            if failed < 3:
                print(f"    [w{worker_id}] Position failed: {e}")

    engine.quit()
    return fens, evals, wdls, failed


def label_with_stockfish(positions, sf_path, depth=16, threads=4, hash_mb=256):
    """Label positions with Stockfish evaluation using parallel workers."""
    import concurrent.futures

    # Use multiple SF instances for parallelism instead of one fat instance.
    # Each worker gets fewer threads but we run many in parallel.
    num_workers = max(1, threads // 2)  # e.g. 8 threads -> 4 workers x 2 threads
    threads_per = max(1, threads // num_workers)
    hash_per = max(64, hash_mb // num_workers)

    print(f"[+] Labeling {len(positions)} positions with Stockfish depth {depth}")
    print(f"    Engine: {sf_path}")
    print(f"    Workers: {num_workers} x {threads_per} threads, {hash_per}MB hash each")

    # Split positions into chunks
    chunk_size = (len(positions) + num_workers - 1) // num_workers
    chunks = []
    for i in range(num_workers):
        start = i * chunk_size
        end = min(start + chunk_size, len(positions))
        if start < end:
            chunks.append((positions[start:end], sf_path, depth, threads_per, hash_per, i))

    t0 = time.time()

    # Run workers in parallel using ProcessPoolExecutor
    all_fens = []
    all_evals = []
    all_wdls = []
    total_failed = 0

    # Use ThreadPoolExecutor since each worker spawns its own SF subprocess
    with concurrent.futures.ThreadPoolExecutor(max_workers=num_workers) as executor:
        futures = [executor.submit(_label_chunk, chunk) for chunk in chunks]

        # Monitor progress
        done_count = 0
        for future in concurrent.futures.as_completed(futures):
            fens, evals_list, wdls_list, failed = future.result()
            all_fens.extend(fens)
            all_evals.extend(evals_list)
            all_wdls.extend(wdls_list)
            total_failed += failed
            done_count += 1
            elapsed = time.time() - t0
            rate = len(all_fens) / elapsed if elapsed > 0 else 0
            remaining = len(positions) - len(all_fens) - total_failed
            eta = remaining / rate if rate > 0 else 0
            print(f"    Worker {done_count}/{num_workers} done. "
                  f"{len(all_fens)} labeled ({rate:.0f} pos/s, ETA {eta/60:.1f}m, failed={total_failed})")

    elapsed = time.time() - t0
    print(f"[+] Labeled {len(all_fens)} positions in {elapsed:.0f}s "
          f"({len(all_fens)/elapsed:.0f} pos/s), {total_failed} failures")

    return all_fens, np.array(all_evals, dtype=np.int16), np.array(all_wdls, dtype=np.uint8)


def main():
    parser = argparse.ArgumentParser(description="Generate NNUE training data")
    parser.add_argument("--pgn-dir", default="E:/Engine-Pgns", help="Directory with PGN files")
    parser.add_argument("--sf-path", default="E:/ChessEngines/stockfish_17.1/stockfish-windows-x86-64-avx2.exe")
    parser.add_argument("--positions", type=int, default=100000, help="Target position count")
    parser.add_argument("--depth", type=int, default=16, help="Stockfish search depth")
    parser.add_argument("--sf-threads", type=int, default=8, help="Stockfish threads")
    parser.add_argument("--sf-hash", type=int, default=512, help="Stockfish hash MB")
    parser.add_argument("--out", default="nnue_data.npz", help="Output file")
    parser.add_argument("--sample-rate", type=float, default=0.12, help="Position sampling rate from games")
    args = parser.parse_args()

    print(f"=== NNUE Data Generation ===")
    print(f"  Target: {args.positions} positions")
    print(f"  Stockfish depth: {args.depth}")
    print(f"  PGN dir: {args.pgn_dir}")
    print(f"  Output: {args.out}")
    print()

    # Sample more than needed (some will fail SF labeling)
    oversample = int(args.positions * 1.1)
    positions = sample_positions_from_pgn(args.pgn_dir, oversample, sample_rate=args.sample_rate)
    if not positions:
        print("[!] No positions sampled, exiting")
        sys.exit(1)

    fens, evals, wdls = label_with_stockfish(
        positions, args.sf_path,
        depth=args.depth, threads=args.sf_threads, hash_mb=args.sf_hash
    )

    # Convert FENs to numpy array of strings
    fen_array = np.array(fens, dtype=object)

    np.savez_compressed(args.out, fens=fen_array, evals=evals, wdls=wdls)
    print(f"\n[+] Saved {len(fens)} positions to {args.out}")
    print(f"    Eval range: [{evals.min()}, {evals.max()}], mean={evals.mean():.1f}, std={evals.std():.1f}")
    print(f"    WDL distribution: W={np.sum(wdls==2)}, D={np.sum(wdls==1)}, L={np.sum(wdls==0)}")
    print(f"    File size: {os.path.getsize(args.out) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
