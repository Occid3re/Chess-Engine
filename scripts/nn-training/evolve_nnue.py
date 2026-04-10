#!/usr/bin/env python3
"""
NNUE Self-Play Evolution Loop.

The path to a BEAST:
1. Start with trained NNUE weights (v1)
2. Generate self-play games with the current champion engine
3. Label positions with the engine's own search (depth 12)
4. Retrain NNUE on the new data (fine-tune from current weights)
5. Test new weights vs champion in CuteChess match
6. If new wins → becomes champion. Repeat.

Also supports pure mutation: perturb weights slightly, test, keep winners.

Usage:
  python evolve_nnue.py --champion-weights weights.bin --generations 100

Requires: CuteChess CLI, Stockfish, Java engine JAR.
"""
import argparse
import copy
import glob
import json
import os
import random
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

import chess
import chess.engine
import chess.pgn
import io
import numpy as np

# ── Configuration ──

ENGINE_JAR = r"C:\Development\Chess-Engine\target\chess-engine-4.0.0-uci.jar"
JAVA_EXE = r"C:\Users\juliu\.jdks\openjdk-25\bin\java.exe"
CUTECHESS_CLI = r"C:\Program Files (x86)\Cute Chess\cutechess-cli.exe"
STOCKFISH_EXE = r"E:\ChessEngines\stockfish_17.1\stockfish-windows-x86-64-avx2.exe"
SYZYGY_NATIVE = r"C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll"
SYZYGY_PATHS = r"C:\Syzygy"

NNUE_WEIGHTS_DIR = r"C:\Development\Chess-Engine\src\main\resources\nn\nnue"
EVOLVE_DIR = r"C:\Development\Chess-Engine\scripts\nn-training\evolve"

# Self-play settings
SELF_PLAY_GAMES = 200        # games per self-play generation
SELF_PLAY_TC = "5+0.05"     # time control for self-play
SELF_PLAY_CONCURRENCY = 3   # parallel games

# Labeling settings
LABEL_DEPTH = 12             # SF depth for labeling self-play positions
LABEL_WORKERS = 8            # parallel SF instances
POSITIONS_PER_GAME = 8       # positions sampled per game

# Training settings
FINETUNE_EPOCHS = 30         # epochs for fine-tuning
FINETUNE_LR = 3e-4           # lower LR for fine-tuning (vs 1e-3 for initial)
FINETUNE_BATCH = 8192

# Match settings
MATCH_GAMES = 100            # games for champion vs challenger
MATCH_TC = "10+0.1"         # time control for validation match
MIN_ELO_GAIN = 0            # accept if Elo gain > this (0 = any improvement)

# Mutation settings
MUTATION_SCALE = 0.02        # stddev of gaussian noise added to weights
NUM_MUTATIONS = 3            # mutations to try per generation


def engine_cmd(weights_path, extra_props=""):
    """Build Java engine command for CuteChess."""
    props = (
        f"-Dchessengine.eval.mode=nnue "
        f"-Dchessengine.nnue.weights={weights_path} "
        f"-Dchessengine.searchThreads=4 "
        f"-Dchessengine.lazySmpThreads=4 "
        f"-Dchessengine.tt.mb=256 "
        f"-Dchessengine.syzygy.nativeLibrary={SYZYGY_NATIVE} "
        f"-Dchessengine.syzygy.paths={SYZYGY_PATHS} "
        f"{extra_props}"
    )
    return (
        f'"{JAVA_EXE}" -Xms4g -Xmx4g --enable-native-access=ALL-UNNAMED --enable-preview '
        f'-XX:+UseG1GC {props} -jar "{ENGINE_JAR}"'
    )


def run_match(champion_weights, challenger_weights, num_games=MATCH_GAMES, tc=MATCH_TC):
    """Run a CuteChess match between champion and challenger. Returns (wins, losses, draws)."""
    pgn_file = os.path.join(EVOLVE_DIR, "match.pgn")
    if os.path.exists(pgn_file):
        os.remove(pgn_file)

    # Write engine commands to bat files for CuteChess
    champ_bat = os.path.join(EVOLVE_DIR, "champion.bat")
    chall_bat = os.path.join(EVOLVE_DIR, "challenger.bat")

    with open(champ_bat, 'w') as f:
        f.write(f'@echo off\n{engine_cmd(champion_weights)}\n')
    with open(chall_bat, 'w') as f:
        f.write(f'@echo off\n{engine_cmd(challenger_weights)}\n')

    rounds = num_games // 2
    cmd = [
        CUTECHESS_CLI,
        "-engine", f"cmd={champ_bat}", "name=champion", "proto=uci",
        "-engine", f"cmd={chall_bat}", "name=challenger", "proto=uci",
        "-each", f"tc={tc}", "option.Threads=4",
        "-rounds", str(rounds), "-games", "2", "-repeat",
        "-concurrency", str(SELF_PLAY_CONCURRENCY),
        "-pgnout", pgn_file,
        "-tournament", "round-robin",
    ]

    print(f"  Running match: {num_games} games at {tc}...")
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=7200)

    # Parse PGN results
    if not os.path.exists(pgn_file):
        print(f"  [!] No PGN file produced")
        return 0, 0, 0

    chall_wins, chall_losses, draws = 0, 0, 0
    with open(pgn_file, 'r') as f:
        content = f.read()
        games = content.split('[Event')
        for game in games:
            if 'Result' not in game or 'White' not in game:
                continue
            is_chall_white = 'challenger' in game.split('White')[1].split(']')[0]
            if '1-0' in game.split('Result')[1].split(']')[0]:
                if is_chall_white:
                    chall_wins += 1
                else:
                    chall_losses += 1
            elif '0-1' in game.split('Result')[1].split(']')[0]:
                if is_chall_white:
                    chall_losses += 1
                else:
                    chall_wins += 1
            elif '1/2' in game.split('Result')[1].split(']')[0]:
                draws += 1

    return chall_wins, chall_losses, draws


def self_play_and_label(weights_path, num_games=SELF_PLAY_GAMES):
    """Generate self-play games with current engine, then label positions with SF."""
    pgn_file = os.path.join(EVOLVE_DIR, "selfplay.pgn")
    if os.path.exists(pgn_file):
        os.remove(pgn_file)

    # Self-play via CuteChess (engine plays itself)
    bat_file = os.path.join(EVOLVE_DIR, "selfplay_engine.bat")
    with open(bat_file, 'w') as f:
        f.write(f'@echo off\n{engine_cmd(weights_path)}\n')

    rounds = num_games // 2
    cmd = [
        CUTECHESS_CLI,
        "-engine", f"cmd={bat_file}", "name=engine1", "proto=uci",
        "-engine", f"cmd={bat_file}", "name=engine2", "proto=uci",
        "-each", f"tc={SELF_PLAY_TC}", "option.Threads=4",
        "-rounds", str(rounds), "-games", "2", "-repeat",
        "-concurrency", str(SELF_PLAY_CONCURRENCY),
        "-pgnout", pgn_file,
        "-tournament", "round-robin",
    ]

    print(f"  Self-play: {num_games} games at {SELF_PLAY_TC}...")
    subprocess.run(cmd, capture_output=True, text=True, timeout=7200)

    if not os.path.exists(pgn_file):
        print("  [!] Self-play produced no games")
        return None

    # Sample positions from self-play games
    print(f"  Sampling positions from self-play games...")
    positions = []
    with open(pgn_file, 'r', encoding='utf-8', errors='replace') as f:
        while True:
            try:
                game = chess.pgn.read_game(f)
            except Exception:
                continue
            if game is None:
                break

            result_str = game.headers.get("Result", "*")
            if result_str == "1-0":
                wdl = 2
            elif result_str == "0-1":
                wdl = 0
            elif result_str == "1/2-1/2":
                wdl = 1
            else:
                continue

            board = game.board()
            ply = 0
            plies = []
            for move in game.mainline_moves():
                is_cap = board.is_capture(move)
                board.push(move)
                ply += 1
                if ply >= 10 and not board.is_check() and not is_cap:
                    plies.append((board.fen(), wdl))

            # Sample fixed number per game
            if len(plies) > POSITIONS_PER_GAME:
                plies = random.sample(plies, POSITIONS_PER_GAME)
            positions.extend(plies)

    print(f"  Sampled {len(positions)} positions from self-play")

    if len(positions) < 100:
        print("  [!] Too few positions, skipping labeling")
        return None

    # Label with Stockfish
    print(f"  Labeling {len(positions)} positions with SF depth {LABEL_DEPTH}...")
    from generate_nnue_data import label_with_stockfish
    fens, evals, wdls = label_with_stockfish(
        positions, STOCKFISH_EXE,
        depth=LABEL_DEPTH, threads=LABEL_WORKERS, hash_mb=256
    )

    # Save as npz
    out_path = os.path.join(EVOLVE_DIR, "selfplay_data.npz")
    np.savez_compressed(out_path,
                        fens=np.array(fens, dtype=object),
                        evals=evals, wdls=wdls)
    print(f"  Saved {len(fens)} labeled positions to {out_path}")
    return out_path


def finetune_nnue(base_weights_path, data_path, output_path):
    """Fine-tune NNUE weights on new data."""
    print(f"  Fine-tuning NNUE on {data_path}...")
    cmd = [
        sys.executable, "-u", "train_nnue.py",
        "--data", data_path,
        "--out", output_path,
        "--epochs", str(FINETUNE_EPOCHS),
        "--batch-size", str(FINETUNE_BATCH),
        "--lr", str(FINETUNE_LR),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=3600,
                          cwd=os.path.dirname(os.path.abspath(__file__)))
    print(result.stdout[-500:] if result.stdout else "")
    if result.returncode != 0:
        print(f"  [!] Training failed: {result.stderr[-300:]}")
        return False
    return os.path.exists(output_path)


def mutate_weights(weights_path, output_path, scale=MUTATION_SCALE):
    """Create a mutated copy of NNUE weights by adding gaussian noise."""
    with open(weights_path, 'rb') as f:
        data = bytearray(f.read())

    # Skip header (4 ints = 16 bytes)
    header_size = 16
    # Mutate weight bytes (int16 values)
    weights_data = np.frombuffer(data[header_size:], dtype=np.int16).copy()

    # Add gaussian noise scaled to weight magnitude
    noise = np.random.normal(0, scale, size=weights_data.shape)
    weight_magnitudes = np.abs(weights_data).astype(np.float32) + 1.0
    perturbation = (noise * weight_magnitudes).astype(np.int16)
    weights_data += perturbation

    # Write back
    result = bytearray(data[:header_size]) + weights_data.tobytes()
    # Preserve the footer (last 4 bytes: L3 bias + output scale)
    footer_start = header_size + len(weights_data) * 2
    if footer_start < len(data):
        result += data[footer_start:]

    with open(output_path, 'wb') as f:
        f.write(result)

    return output_path


def elo_from_score(wins, losses, draws):
    """Compute Elo difference from match result."""
    total = wins + losses + draws
    if total == 0:
        return 0.0
    score = (wins + draws / 2.0) / total
    if score <= 0 or score >= 1:
        return 400.0 if score >= 1 else -400.0
    import math
    return -400.0 * math.log10((1.0 / score) - 1.0)


def main():
    parser = argparse.ArgumentParser(description="NNUE Self-Play Evolution")
    parser.add_argument("--champion-weights",
                       default=os.path.join(NNUE_WEIGHTS_DIR, "weights.bin"))
    parser.add_argument("--generations", type=int, default=50)
    parser.add_argument("--mode", choices=["selfplay", "mutate", "both"], default="both",
                       help="selfplay=retrain on self-play, mutate=perturb weights, both=alternate")
    parser.add_argument("--classic-baseline", action="store_true",
                       help="Also test each champion against classic eval")
    args = parser.parse_args()

    os.makedirs(EVOLVE_DIR, exist_ok=True)

    champion_path = args.champion_weights
    if not os.path.exists(champion_path):
        print(f"[!] Champion weights not found: {champion_path}")
        sys.exit(1)

    print(f"=== NNUE Evolution Loop ===")
    print(f"  Champion: {champion_path}")
    print(f"  Mode: {args.mode}")
    print(f"  Generations: {args.generations}")
    print()

    history = []

    for gen in range(1, args.generations + 1):
        print(f"\n{'='*60}")
        print(f"  GENERATION {gen}/{args.generations}")
        print(f"{'='*60}")

        best_elo = -999
        best_path = None

        # ── Self-play retraining ──
        if args.mode in ("selfplay", "both") and gen % 2 == 1:
            print("\n[Self-play retrain]")
            data_path = self_play_and_label(champion_path)
            if data_path:
                challenger_path = os.path.join(EVOLVE_DIR, f"gen{gen}_selfplay.bin")
                if finetune_nnue(champion_path, data_path, challenger_path):
                    w, l, d = run_match(champion_path, challenger_path)
                    elo = elo_from_score(w, l, d)
                    print(f"  Self-play challenger: {w}W-{l}L-{d}D = {elo:+.1f} Elo")
                    if elo > best_elo:
                        best_elo = elo
                        best_path = challenger_path

        # ── Mutation ──
        if args.mode in ("mutate", "both"):
            print("\n[Mutation]")
            for mut_idx in range(NUM_MUTATIONS):
                scale = MUTATION_SCALE * (0.5 + random.random())  # vary mutation strength
                challenger_path = os.path.join(EVOLVE_DIR, f"gen{gen}_mut{mut_idx}.bin")
                mutate_weights(champion_path, challenger_path, scale)

                w, l, d = run_match(champion_path, challenger_path, num_games=60)
                elo = elo_from_score(w, l, d)
                print(f"  Mutation {mut_idx} (scale={scale:.3f}): {w}W-{l}L-{d}D = {elo:+.1f} Elo")

                if elo > best_elo:
                    best_elo = elo
                    best_path = challenger_path

        # ── Accept or reject ──
        if best_path and best_elo > MIN_ELO_GAIN:
            print(f"\n  >>> NEW CHAMPION! +{best_elo:.1f} Elo <<<")
            # Copy to champion slot
            backup = os.path.join(EVOLVE_DIR, f"champion_gen{gen-1}.bin")
            shutil.copy2(champion_path, backup)
            shutil.copy2(best_path, champion_path)
            history.append({"gen": gen, "elo": best_elo, "method": "accepted"})
        else:
            print(f"\n  No improvement (best: {best_elo:+.1f} Elo). Champion retained.")
            history.append({"gen": gen, "elo": best_elo, "method": "rejected"})

        # ── Optional classic baseline ──
        if args.classic_baseline and gen % 5 == 0:
            print("\n[Classic baseline check]")
            classic_bat = os.path.join(EVOLVE_DIR, "classic.bat")
            with open(classic_bat, 'w') as f:
                classic_cmd = (
                    f'@echo off\n"{JAVA_EXE}" -Xms4g -Xmx4g '
                    f'--enable-native-access=ALL-UNNAMED --enable-preview -XX:+UseG1GC '
                    f'-Dchessengine.searchThreads=4 -Dchessengine.lazySmpThreads=4 '
                    f'-Dchessengine.tt.mb=256 '
                    f'-Dchessengine.syzygy.nativeLibrary={SYZYGY_NATIVE} '
                    f'-Dchessengine.syzygy.paths={SYZYGY_PATHS} '
                    f'-jar "{ENGINE_JAR}"\n'
                )
                f.write(classic_cmd)

            champ_bat = os.path.join(EVOLVE_DIR, "champion.bat")
            with open(champ_bat, 'w') as f:
                f.write(f'@echo off\n{engine_cmd(champion_path)}\n')

            cmd = [
                CUTECHESS_CLI,
                "-engine", f"cmd={champ_bat}", "name=nnue-champ", "proto=uci",
                "-engine", f"cmd={classic_bat}", "name=classic", "proto=uci",
                "-each", f"tc={MATCH_TC}", "option.Threads=4",
                "-rounds", "30", "-games", "2", "-repeat",
                "-concurrency", str(SELF_PLAY_CONCURRENCY),
                "-pgnout", os.path.join(EVOLVE_DIR, "vs_classic.pgn"),
                "-tournament", "round-robin",
            ]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=7200)
            # Parse quickly
            pgn_path = os.path.join(EVOLVE_DIR, "vs_classic.pgn")
            if os.path.exists(pgn_path):
                with open(pgn_path) as pf:
                    content = pf.read()
                nnue_w = content.count('"nnue-champ"]') and content.count('"1-0"]')
                print(f"  vs Classic: see {pgn_path}")

        # Save history
        with open(os.path.join(EVOLVE_DIR, "evolution_history.json"), 'w') as f:
            json.dump(history, f, indent=2)

        print(f"\n  History: {[f'gen{h['gen']}:{h['elo']:+.0f}' for h in history[-5:]]}")

    print(f"\n{'='*60}")
    print(f"  EVOLUTION COMPLETE ({args.generations} generations)")
    print(f"  Champion: {champion_path}")
    total_gain = sum(h['elo'] for h in history if h['method'] == 'accepted')
    print(f"  Total Elo gained: {total_gain:+.1f}")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
