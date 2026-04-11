#!/usr/bin/env python3
"""
Classic Eval Evolution via Genetic Mutation + Self-Play Selection.

Mutates the tuning YAML parameters (material weights, search params,
module weights) and tests each mutant against the champion in CuteChess.
Winners become the new champion.

Also periodically tests against v3.6.9 as external baseline.

Usage:
  python evolve.py --generations 30
"""
import argparse
import copy
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import time

import yaml

ENGINE_JAR = r"C:\Development\Chess-Engine\target\chess-engine-4.0.0-uci.jar"
JAVA_EXE = r"C:\Users\juliu\.jdks\openjdk-25\bin\java.exe"
CUTECHESS = r"C:\Program Files (x86)\Cute Chess\cutechess-cli.exe"
SYZYGY_NATIVE = r"C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll"
SYZYGY_PATHS = r"C:\Syzygy"
TUNING_FILE = r"C:\Development\Chess-Engine\src\main\resources\tuning\seed-tunings.yaml"
EVOLVE_DIR = r"C:\Development\Chess-Engine\scripts\nn-training\evolve"
V369_BAT = r"E:\ChessEngines\v3.6.9\baseline.bat"

MATCH_GAMES = 40  # per mutation test (fast feedback)
MATCH_TC = "10+0.1"
MATCH_CONCURRENCY = 3
MATCH_THREADS = 4
NUM_MUTATIONS = 4  # mutations per generation

# Parameters to mutate and their noise scales (relative to value)
# Higher scale = more aggressive exploration
MUTABLE_PARAMS = {
    # Module weights (high impact)
    "materialmodule.midgame": 0.05,
    "materialmodule.endgame": 0.08,
    "pawnstructuremodule.midgame": 0.08,
    "pawnstructuremodule.endgame": 0.08,
    "activitymodule.midgame": 0.08,
    "activitymodule.endgame": 0.08,
    "threatmodule.midgame": 0.08,
    "threatmodule.endgame": 0.10,
    "kingsafetymodule.midgame": 0.08,
    "kingsafetymodule.endgame": 0.10,
    # Piece values
    "material.pawnvalue": 0.03,
    "material.knightvalue": 0.03,
    "material.bishopvalue": 0.03,
    "material.rookvalue": 0.03,
    "material.queenvalue": 0.03,
    "material.bishoppairbonus": 0.10,
    # Search params (medium impact)
    "search.nullbasereduction": 0.05,
    "search.nulldepthweight": 0.10,
    "search.nullmaterialweight": 0.10,
    "search.fpmargindepth1": 0.08,
    "search.fpmargindepth2": 0.08,
    "search.razormargindepth1": 0.08,
    "search.razormargindepth2": 0.08,
    "search.lmrScaleDivisor": 0.05,
    "search.probcutmargin": 0.08,
    # King safety
    "kingsafety.attackweightknight": 0.10,
    "kingsafety.attackweightbishop": 0.10,
    "kingsafety.attackweightrook": 0.10,
    "kingsafety.attackweightqueen": 0.10,
    "kingsafety.missingpawnshieldpenalty": 0.10,
}


def load_tuning(path):
    """Load tuning YAML."""
    with open(path, 'r') as f:
        return yaml.safe_load(f)


def save_tuning(data, path):
    """Save tuning YAML."""
    with open(path, 'w') as f:
        yaml.dump(data, f, default_flow_style=False, sort_keys=False)


def mutate_tuning(base_tuning, intensity=1.0):
    """Create a mutated copy of the tuning config."""
    mutant = copy.deepcopy(base_tuning)
    config = mutant["population"][0]

    # Mutate module weights
    modules = config.get("evaluation", {}).get("modules", {})
    for mod_name, mod_weights in modules.items():
        for phase in ("midgame", "endgame"):
            key = f"{mod_name}.{phase}"
            if key in MUTABLE_PARAMS and phase in mod_weights:
                scale = MUTABLE_PARAMS[key] * intensity
                old = mod_weights[phase]
                noise = random.gauss(0, scale * abs(old) + 0.01)
                mod_weights[phase] = round(old + noise, 2)

    # Mutate numeric parameters
    params = config.get("numericParameters", {})
    for key, scale_base in MUTABLE_PARAMS.items():
        if key.startswith(("materialmodule", "pawnstructuremodule", "activitymodule",
                          "threatmodule", "kingsafetymodule")):
            continue  # already handled above
        if key in params:
            scale = scale_base * intensity
            old = params[key]
            noise = random.gauss(0, scale * abs(old) + 0.1)
            params[key] = round(old + noise, 2)

    return mutant


def engine_bat(tuning_path, name):
    """Write a bat file that uses a specific tuning file."""
    bat = os.path.join(EVOLVE_DIR, f"{name}.bat")
    with open(bat, 'w') as f:
        f.write(f'''@echo off
"{JAVA_EXE}" -Xms4g -Xmx4g --enable-native-access=ALL-UNNAMED --enable-preview -XX:+UseG1GC ^
  -Dchessengine.searchThreads={MATCH_THREADS} ^
  -Dchessengine.lazySmpThreads=4 ^
  -Dchessengine.rootParallelLimit=120 ^
  -Dchessengine.tt.mb=256 ^
  -Dchessengine.tuning.file={tuning_path} ^
  -Dchessengine.syzygy.nativeLibrary={SYZYGY_NATIVE} ^
  -Dchessengine.syzygy.paths={SYZYGY_PATHS} ^
  -jar "{ENGINE_JAR}"
''')
    return bat


def run_match(bat_a, name_a, bat_b, name_b, num_games=MATCH_GAMES, tc=MATCH_TC):
    """Run CuteChess match. Returns (a_wins, a_losses, draws)."""
    pgn = os.path.join(EVOLVE_DIR, f"match_{name_a}_vs_{name_b}.pgn")
    if os.path.exists(pgn): os.remove(pgn)

    rounds = max(1, num_games // 2)
    cmd = [
        CUTECHESS,
        "-engine", f"cmd={bat_a}", f"name={name_a}", "proto=uci",
        "-engine", f"cmd={bat_b}", f"name={name_b}", "proto=uci",
        "-each", f"tc={tc}", f"option.Threads={MATCH_THREADS}",
        "-rounds", str(rounds), "-games", "2", "-repeat",
        "-concurrency", str(MATCH_CONCURRENCY),
        "-pgnout", pgn,
        "-tournament", "round-robin",
    ]

    try:
        subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
    except subprocess.TimeoutExpired:
        print("    [!] Match timed out")
        return 0, 0, 0

    if not os.path.exists(pgn): return 0, 0, 0

    a_w, a_l, draws = 0, 0, 0
    with open(pgn, 'r', errors='replace') as f:
        content = f.read()
    games = re.findall(r'\[White "([^"]+)"\].*?\[Result "([^"]+)"\]', content, re.DOTALL)
    for white, result in games:
        if result == "1-0":
            if white == name_a: a_w += 1
            else: a_l += 1
        elif result == "0-1":
            if white == name_a: a_l += 1
            else: a_w += 1
        elif "1/2" in result:
            draws += 1
    return a_w, a_l, draws


def elo(w, l, d):
    total = w + l + d
    if total == 0: return 0.0
    score = (w + d / 2.0) / total
    if score <= 0: return -400.0
    if score >= 1: return 400.0
    return -400.0 * math.log10((1.0 / score) - 1.0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--generations", type=int, default=30)
    parser.add_argument("--intensity", type=float, default=1.0, help="Mutation intensity multiplier")
    args = parser.parse_args()

    os.makedirs(EVOLVE_DIR, exist_ok=True)
    champion_yaml = os.path.join(EVOLVE_DIR, "champion.yaml")

    # Initialize champion from current tuning
    if not os.path.exists(champion_yaml):
        shutil.copy2(TUNING_FILE, champion_yaml)

    champion_tuning = load_tuning(champion_yaml)
    champ_bat = engine_bat(champion_yaml, "champion")

    print(f"=== Classic Eval Evolution ===")
    print(f"  Generations: {args.generations}")
    print(f"  Mutations/gen: {NUM_MUTATIONS}")
    print(f"  Games/test: {MATCH_GAMES}")
    print(f"  TC: {MATCH_TC}")
    print(f"  Intensity: {args.intensity}")
    print()

    history = []
    total_gained = 0

    for gen in range(1, args.generations + 1):
        t0 = time.time()
        print(f"\n{'='*50}")
        print(f"  GEN {gen}/{args.generations} (total gained: +{total_gained:.0f})")
        print(f"{'='*50}")

        best_elo = -999
        best_yaml_path = None

        for mi in range(NUM_MUTATIONS):
            # Vary intensity: some mutations are gentle, some aggressive
            intensity = args.intensity * (0.3 + random.random() * 1.4)
            mutant_tuning = mutate_tuning(champion_tuning, intensity)
            mutant_yaml = os.path.join(EVOLVE_DIR, f"gen{gen}_mut{mi}.yaml")
            save_tuning(mutant_tuning, mutant_yaml)
            mut_bat = engine_bat(mutant_yaml, f"mut{mi}")

            w, l, d = run_match(mut_bat, f"mut{mi}", champ_bat, "champion")
            e = elo(w, l, d)
            total = w + l + d
            pct = (w + d/2) / max(1, total) * 100
            print(f"    Mut {mi} (i={intensity:.2f}): {w}W-{l}L-{d}D ({pct:.0f}%) = {e:+.0f} Elo")

            if e > best_elo:
                best_elo = e
                best_yaml_path = mutant_yaml

        # Accept?
        if best_yaml_path and best_elo > 0:
            print(f"\n  >>> ACCEPTED +{best_elo:.0f} Elo <<<")
            backup = os.path.join(EVOLVE_DIR, f"champion_gen{gen-1}.yaml")
            shutil.copy2(champion_yaml, backup)
            shutil.copy2(best_yaml_path, champion_yaml)
            champion_tuning = load_tuning(champion_yaml)
            champ_bat = engine_bat(champion_yaml, "champion")
            total_gained += best_elo
            history.append({"gen": gen, "elo": best_elo, "accepted": True,
                           "time_s": int(time.time() - t0)})
        else:
            print(f"\n  Rejected (best: {best_elo:+.0f})")
            history.append({"gen": gen, "elo": best_elo, "accepted": False,
                           "time_s": int(time.time() - t0)})

        # vs v3.6.9 every 5 generations
        if gen % 5 == 0 and os.path.exists(V369_BAT):
            print("\n  [vs v3.6.9]")
            w, l, d = run_match(champ_bat, "champion", V369_BAT, "v3.6.9", num_games=60)
            e = elo(w, l, d)
            print(f"    vs v3.6.9: {w}W-{l}L-{d}D = {e:+.0f} Elo")
            history[-1]["vs_v369"] = e

        # Save history
        with open(os.path.join(EVOLVE_DIR, "history.json"), 'w') as f:
            json.dump(history, f, indent=2)

        elapsed = time.time() - t0
        print(f"\n  Gen time: {elapsed:.0f}s. Total: +{total_gained:.0f} Elo")

    # Final: copy champion tuning to production
    print(f"\n{'='*50}")
    print(f"  EVOLUTION COMPLETE: +{total_gained:.0f} Elo over {args.generations} gens")
    accepted = len([h for h in history if h.get("accepted")])
    print(f"  Accepted: {accepted}/{len(history)}")
    print(f"{'='*50}")

    # Deploy champion to production tuning file
    shutil.copy2(champion_yaml, TUNING_FILE)
    print(f"  Champion deployed to {TUNING_FILE}")
    print(f"  Rebuild with: ./mvnw.cmd -DskipTests package")


if __name__ == "__main__":
    main()
