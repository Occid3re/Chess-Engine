#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Advanced tuning loop that evaluates the engine via cutechess-cli matches."""

from __future__ import annotations

import argparse
import dataclasses
import math
import os
import random
import re
import shlex
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Sequence
import re


try:
    from auto_tuning_loop import (
        AcceptanceDecision,
        SeedTuningOptimizer,
        _effective_mut_frac,
        _mut_frac_next,
        find_project_root,
        timestamp,
    )
except ImportError as exc:  # pragma: no cover - defensive guard for manual execution
    raise SystemExit(
        "Failed to import tuning helpers from auto_tuning_loop.py. "
        "Ensure the script is executed from the project's scripts directory."
    ) from exc


SCORE_PATTERN = re.compile(
    r"Score of (?P<first>.+?) vs (?P<second>.+?):\s+"
    r"(?P<wins>\d+)\s+-\s+(?P<losses>\d+)\s+-\s+(?P<draws>\d+)\s+\[(?P<score>[0-9.]+)\]"
)


@dataclass
class MatchResult:
    engine_name: str
    opponent_name: str
    wins: int
    losses: int
    draws: int
    score: float
    opponent_elo: float
    duration_s: float
    stdout: str
    stderr: str
    command: Sequence[str]

    @property
    def total_games(self) -> int:
        return self.wins + self.losses + self.draws

    @property
    def draws_fraction(self) -> float:
        return 0.0 if self.total_games == 0 else self.draws / self.total_games

    @property
    def points(self) -> float:
        return self.wins + 0.5 * self.draws

    @property
    def points_fraction(self) -> float:
        return 0.0 if self.total_games == 0 else self.points / self.total_games

    @property
    def elo_diff(self) -> float:
        score = min(max(self.points_fraction, 1e-4), 1 - 1e-4)
        return -400.0 * math.log10((1.0 / score) - 1.0)

    @property
    def implied_rating(self) -> float:
        return self.opponent_elo + self.elo_diff

    @property
    def avg_time_per_game(self) -> float:
        return 0.0 if self.total_games == 0 else self.duration_s / self.total_games

    def summary(self) -> str:
        return (
            f"Score {self.engine_name} vs {self.opponent_name}: "
            f"{self.wins}-{self.losses}-{self.draws} ({self.points_fraction*100:.1f}% points), "
            f"ΔElo={self.elo_diff:+.1f}, implied rating={self.implied_rating:.1f}, "
            f"avg time/game={self.avg_time_per_game:.2f}s over {self.total_games} games"
        )


@dataclass
class PseudoTestResult:
    total: int
    failures: int
    errors: int
    duration_s: float

    @property
    def successes(self) -> int:
        return self.total - self.failures - self.errors


def log_jsonl(path: Path, record: Dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json_dumps(record) + "\n")


def json_dumps(record: Dict[str, object]) -> str:
    import json

    return json.dumps(record, ensure_ascii=False, sort_keys=True)


def save_iteration_config(base_dir: Path, iteration: int, result: MatchResult, lines: List[str]) -> Path:
    filename = (
        f"Iteration-{iteration:03d}-"
        f"Elo{result.implied_rating:+.0f}-"
        f"Score{result.points_fraction*100:05.1f}.yaml"
    )
    path = base_dir / filename
    path.parent.mkdir(parents=True, exist_ok=True)
    content = "\n".join(lines)
    if not content.endswith("\n"):
        content += "\n"
    path.write_text(content, encoding="utf-8")
    return path


def resolve_optional_path(value: Optional[str], *, must_exist: bool) -> Optional[Path]:
    if value is None:
        return None
    path = Path(os.path.expanduser(value)).resolve()
    if must_exist and not path.exists():
        return None
    return path


def expand_if_path(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    if os.sep in value or (os.altsep and os.altsep in value) or value.startswith("."):
        return os.path.expanduser(value)
    return value


def detect_latest_jar(project_root: Path) -> Optional[Path]:
    jars = sorted(project_root.glob("target/chess-engine-*-uci.jar"))
    if not jars:
        return None
    return max(jars, key=lambda p: p.stat().st_mtime)


def maybe_build_jar(project_root: Path, mvn: str, extra_args: Sequence[str]) -> None:
    cmd = [mvn, "-DskipTests", "-Djava.version=21", "-Dmaven.compiler.release=21", "-Dmaven.compiler.enablePreview=true", "-DargLine=--enable-preview", "package"]
    cmd.extend(extra_args)
    print("Running build command:", " ".join(shlex.quote(c) for c in cmd))
    proc = subprocess.run(cmd, cwd=str(project_root), check=False)
    if proc.returncode != 0:
        raise RuntimeError("Maven package command failed")

# put this near SCORE_PATTERN
FINISHED_RE = re.compile(
    r"^Finished game \d+ \((?P<left>.+?) vs (?P<right>.+?)\):\s*(?P<res>1-0|0-1|1/2-1/2)"
)

# Cutechess summary and per-game lines
SCORE_PATTERN = re.compile(
    r"Score of (?P<first>.+?) vs (?P<second>.+?):\s+"
    r"(?P<wins>\d+)\s*-\s*(?P<losses>\d+)\s*-\s*(?P<draws>\d+)\s*\[(?P<score>[0-9.]+)\]"
)
FINISHED_RE = re.compile(
    r"^Finished game \d+ \((?P<left>.+?) vs (?P<right>.+?)\):\s*(?P<res>1-0|0-1|1/2-1/2)"
)

def parse_score(
        stdout: str,
        engine_name: str,
        opponent_name: str,
        opponent_elo: float,
        duration_s: float,
        command: Sequence[str],
        stderr: str,
        verbose: bool = True,  # you can disable prints by setting this to False
):
    """
    Tally results from per-game lines (robust to engine order),
    with a fallback to the 'Score of A vs B' summary (also robust to either order).
    """
    wins = losses = draws = 0

    if verbose:
        print(f"\n[parse_score] Parsing results for {engine_name} vs {opponent_name}...")

    # ---- Pass 1: count from per-game lines ----
    for raw in stdout.splitlines():
        line = raw.strip()
        m = FINISHED_RE.search(line)
        if not m:
            continue
        left = m.group("left").strip()
        right = m.group("right").strip()
        res = m.group("res")

        if {left, right} != {engine_name, opponent_name}:
            continue

        engine_on_left = (left == engine_name)
        if res == "1-0":
            if engine_on_left: wins += 1
            else: losses += 1
        elif res == "0-1":
            if engine_on_left: losses += 1
            else: wins += 1
        else:
            draws += 1

        if verbose:
            print(f"  Finished game: {left} vs {right} -> {res}")

    total = wins + losses + draws
    if total > 0:
        score = (wins + 0.5 * draws) / total
        if verbose:
            print(f"[parse_score] Counted {total} games: {wins}-{losses}-{draws} ({score*100:.1f}%) from per-game lines.")
        return MatchResult(
            engine_name=engine_name,
            opponent_name=opponent_name,
            wins=wins,
            losses=losses,
            draws=draws,
            score=score,
            opponent_elo=opponent_elo,
            duration_s=duration_s,
            stdout=stdout,
            stderr=stderr,
            command=command,
        )

    # ---- Pass 2: fallback to summary lines ----
    if verbose:
        print("[parse_score] No per-game lines found; falling back to summary parsing...")

    engine_first = None
    opponent_first = None
    for raw in stdout.splitlines():
        line = raw.strip()
        m = SCORE_PATTERN.search(line)
        if not m:
            continue
        first = m.group("first").strip()
        second = m.group("second").strip()
        if first == engine_name and second == opponent_name:
            engine_first = m
        elif first == opponent_name and second == engine_name:
            opponent_first = m

    if engine_first is not None:
        w = int(engine_first.group("wins"))
        l = int(engine_first.group("losses"))
        d = int(engine_first.group("draws"))
    elif opponent_first is not None:
        w = int(opponent_first.group("losses"))
        l = int(opponent_first.group("wins"))
        d = int(opponent_first.group("draws"))
    else:
        print("[parse_score] ERROR: No match summary lines found.")
        print("--- STDOUT ---")
        print(stdout)
        print("--- STDERR ---")
        print(stderr)
        raise ValueError(
            f"Could not parse results for ({engine_name} vs {opponent_name}). "
            "No per-game or summary lines matched."
        )

    total = w + l + d
    score = (w + 0.5 * d) / total if total else 0.0
    if verbose:
        print(f"[parse_score] Fallback result: {w}-{l}-{d} ({score*100:.1f}%) from summary line(s).")

    return MatchResult(
        engine_name=engine_name,
        opponent_name=opponent_name,
        wins=w,
        losses=l,
        draws=d,
        score=score,
        opponent_elo=opponent_elo,
        duration_s=duration_s,
        stdout=stdout,
        stderr=stderr,
        command=command,
    )





def derive_java_args(args: argparse.Namespace, tuning_path: Path) -> List[str]:
    flags: List[str] = []
    if args.engine_xms:
        flags.append(f"-Xms{args.engine_xms}")
    if args.engine_xmx:
        flags.append(f"-Xmx{args.engine_xmx}")
    if args.engine_gc:
        gc_flag = args.engine_gc.lower()
        if gc_flag == "zgc":
            flags.append("-XX:+UseZGC")
        elif gc_flag == "shenandoah":
            flags.append("-XX:+UseShenandoahGC")
        elif gc_flag == "g1":
            flags.append("-XX:+UseG1GC")
        else:
            flags.append(args.engine_gc)
    if args.engine_active_processor_count:
        flags.append(f"-XX:ActiveProcessorCount={args.engine_active_processor_count}")
    for raw in args.java_arg:
        flags.append(raw)
    sysprops = {
        "chessengine.tuning.file": str(tuning_path),
    }
    if args.engine_tt_mb:
        sysprops["chessengine.tt.mb"] = str(args.engine_tt_mb)
    if args.engine_threads:
        sysprops["chessengine.searchThreads"] = str(args.engine_threads)
    if args.engine_lazy_threads:
        sysprops["chessengine.lazySmpThreads"] = str(args.engine_lazy_threads)
    if args.engine_root_limit:
        sysprops["chessengine.rootParallelLimit"] = str(args.engine_root_limit)
    for item in args.java_sysprop:
        key, _, value = item.partition("=")
        if not key:
            continue
        sysprops[key.strip()] = value.strip()
    for key, value in sorted(sysprops.items()):
        flags.append(f"-D{key}={value}")
    flags.append("-jar")
    flags.append(str(args.jar))
    return flags


def build_cutechess_command(args: argparse.Namespace, tuning_path: Path) -> List[str]:
    java_args = derive_java_args(args, tuning_path)
    engine_block = ["-engine", f"name={args.engine_name}", f"cmd={args.java}"] + [f"arg={flag}" for flag in java_args]
    opponent_block = [
        "-engine",
        f"name={args.opponent_name}",
        f"cmd={args.stockfish}",
        "option.UCI_LimitStrength=true",
        f"option.UCI_Elo={args.opponent_elo}",
    ]
    if args.opponent_threads:
        opponent_block.append(f"option.Threads={args.opponent_threads}")
    if args.opponent_hash:
        opponent_block.append(f"option.Hash={args.opponent_hash}")

    cmd: List[str] = [str(args.cutechess_cli)] + engine_block + opponent_block
    cmd += ["-each", "proto=uci", f"tc={args.time_control}"]
    if args.opening_file:
        opening_block = [f"openings.file={args.opening_file}", f"openings.format={args.opening_format}"]
        if args.opening_order:
            opening_block.append(f"openings.order={args.opening_order}")
        if args.opening_random:
            opening_block.append(f"openings.random={args.opening_random}")
        cmd += ["-openings", *opening_block]
    if args.pgn_out:
        cmd += ["-pgnout", str(args.pgn_out)]
    cmd += ["-rounds", str(args.rounds)]
    if args.games:
        cmd += ["-games", str(args.games)]
    cmd += ["-concurrency", str(args.concurrency)]
    if args.cutechess_extra:
        cmd += list(args.cutechess_extra)
    return cmd


def run_match(args: argparse.Namespace, tuning_path: Path) -> MatchResult:
    command = build_cutechess_command(args, tuning_path)

    # === DEBUG INFO BEFORE EXECUTION ===
    print("\n=== [DEBUG] Invoking cutechess-cli ===")
    print("Working directory :", os.getcwd())
    print("Command (split)   :", command)
    print("Command (joined)  :", " ".join(shlex.quote(c) for c in command))
    print("==============================\n")

    start = time.time()
    try:
        proc = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=3600,  # 1h safeguard
        )
    except Exception as e:
        print(f"[DEBUG] Exception while running cutechess-cli: {e}")
        raise

    duration = time.time() - start

    stdout = proc.stdout or ""
    stderr = proc.stderr or ""

    # === DEBUG INFO AFTER EXECUTION ===
    print("\n=== [DEBUG] cutechess-cli finished ===")
    print(f"Return code: {proc.returncode}")
    print(f"Duration   : {duration:.2f} seconds")
    print("--- STDOUT ---")
    print(stdout[:2000] + ("\n... [truncated]" if len(stdout) > 2000 else ""))  # prevent flooding
    print("--- STDERR ---")
    print(stderr[:2000] + ("\n... [truncated]" if len(stderr) > 2000 else ""))
    print("==============================\n")

    # Save raw logs for later inspection
    log_dir = Path("logs/raw_cutechess")
    log_dir.mkdir(parents=True, exist_ok=True)
    timestamp_str = time.strftime("%Y%m%d-%H%M%S")
    log_file = log_dir / f"cutechess_{timestamp_str}.log"
    with log_file.open("w", encoding="utf-8") as f:
        f.write(f"# Command:\n{' '.join(shlex.quote(c) for c in command)}\n\n")
        f.write(f"# Return code: {proc.returncode}\n")
        f.write(f"# Duration: {duration:.2f}s\n\n")
        f.write("### STDOUT ###\n")
        f.write(stdout)
        f.write("\n\n### STDERR ###\n")
        f.write(stderr)
    print(f"[DEBUG] Full cutechess log saved to: {log_file}")

    if proc.returncode != 0:
        raise RuntimeError(
            f"cutechess-cli exited with non-zero status {proc.returncode}.\n"
            f"See log file: {log_file}"
        )

    return parse_score(
        stdout, args.engine_name, args.opponent_name, args.opponent_elo, duration, command, stderr
    )


def adapt_opponent_elo(args: argparse.Namespace, result: MatchResult, *, context: str) -> None:
    """Update the opponent Elo to the rounded-up implied rating from the last match."""

    next_elo = int(math.ceil(result.implied_rating))
    if next_elo != args.opponent_elo:
        print(
            f"[adapt-elo] {context}: setting opponent Elo from {args.opponent_elo} to {next_elo}"
        )
    else:
        print(f"[adapt-elo] {context}: opponent Elo remains at {next_elo}")
    args.opponent_elo = next_elo


def accept_match_candidate(
    best: MatchResult,
    candidate: MatchResult,
    temperature: float,
    allow_worse: bool,
    rng: random.Random,
    min_elo_gain: float,
    min_score_gain: float,
    time_bonus_threshold: float,
) -> AcceptanceDecision:
    elo_delta = candidate.elo_diff - best.elo_diff
    score_delta = candidate.points_fraction - best.points_fraction
    time_delta = best.avg_time_per_game - candidate.avg_time_per_game
    info: Dict[str, float] = {
        "elo_delta": elo_delta,
        "score_delta": score_delta,
        "time_delta": time_delta,
    }

    if elo_delta > min_elo_gain:
        return AcceptanceDecision(True, True, "elo_improved", info)
    if score_delta > min_score_gain:
        return AcceptanceDecision(True, True, "score_improved", info)
    if (
        time_delta > 0.0
        and abs(elo_delta) <= min_elo_gain
        and abs(score_delta) <= min_score_gain
        and time_delta >= time_bonus_threshold * max(best.avg_time_per_game, 1e-9)
    ):
        info["time_ratio"] = time_delta / max(best.avg_time_per_game, 1e-9)
        return AcceptanceDecision(True, True, "time_bonus", info)

    if not allow_worse:
        return AcceptanceDecision(False, False, "rejected", info)

    anneal = math.exp(elo_delta / max(temperature, 1e-9))
    if rng.random() < anneal:
        info["anneal_prob"] = anneal
        return AcceptanceDecision(True, False, "annealed", info)
    info["anneal_prob"] = anneal
    return AcceptanceDecision(False, False, "annealed_reject", info)


def ensure_args(args: argparse.Namespace, project_root: Path) -> None:
    if args.jar is None:
        jar = detect_latest_jar(project_root)
        if jar is None:
            raise SystemExit(
                "Unable to find engine jar in target/. "
                "Run mvn package (or pass --build-jar) or specify --jar explicitly."
            )
        args.jar = jar
    else:
        jar_path = resolve_optional_path(args.jar, must_exist=True)
        if jar_path is None:
            raise SystemExit(f"Jar path '{args.jar}' does not exist")
        args.jar = jar_path

    if args.pgn_out:
        pgn_path = resolve_optional_path(args.pgn_out, must_exist=False)
        args.pgn_out = pgn_path
    if args.opening_file:
        opening_path = resolve_optional_path(args.opening_file, must_exist=True)
        if opening_path is None:
            raise SystemExit(f"Opening file '{args.opening_file}' not found")
        args.opening_file = opening_path

    args.cutechess_cli = expand_if_path(args.cutechess_cli) or "cutechess-cli"
    args.stockfish = expand_if_path(args.stockfish) or "stockfish"
    args.java = expand_if_path(args.java) or "java"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run a cutechess-driven tuning loop against a fixed opponent.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--tuning-path", type=Path, default=Path("src/main/resources/tuning/seed-tunings.yaml"))
    parser.add_argument("--project-root", type=Path, default=None)
    parser.add_argument("--cutechess-cli", type=str, default="cutechess-cli")
    parser.add_argument("--stockfish", type=str, required=True)
    parser.add_argument("--java", type=str, default="java")
    parser.add_argument("--jar", type=str, default=None)
    parser.add_argument("--opponent-name", type=str, default="SF2000")
    parser.add_argument("--opponent-elo", type=int, default=2000)
    parser.add_argument("--opponent-threads", type=int, default=1)
    parser.add_argument("--opponent-hash", type=int, default=64)
    parser.add_argument("--engine-name", type=str, default="Alieknek")
    parser.add_argument("--time-control", type=str, default="10+0.1")
    parser.add_argument("--rounds", type=int, default=8)
    parser.add_argument("--games", type=int, default=0, help="Override total games instead of 2*rounds")
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--pgn-out", type=str, default=None)
    parser.add_argument("--opening-file", type=str, default=None)
    parser.add_argument("--opening-format", type=str, default="pgn")
    parser.add_argument("--opening-order", type=str, default="random")
    parser.add_argument("--opening-random", type=str, default="both")
    parser.add_argument("--cutechess-extra", nargs=argparse.REMAINDER, default=[])

    parser.add_argument("--engine-xms", type=str, default="8g")
    parser.add_argument("--engine-xmx", type=str, default="8g")
    parser.add_argument("--engine-gc", type=str, default="zgc")
    parser.add_argument("--engine-active-processor-count", type=int, default=None)
    parser.add_argument("--engine-tt-mb", type=int, default=1024)
    parser.add_argument("--engine-threads", type=int, default=1)
    parser.add_argument("--engine-lazy-threads", type=int, default=1)
    parser.add_argument("--engine-root-limit", type=int, default=48)
    parser.add_argument("--java-arg", action="append", default=[], help="Additional -X style JVM arguments")
    parser.add_argument("--java-sysprop", action="append", default=[], help="Extra system properties key=value")

    parser.add_argument("--max-iters", type=int, default=0)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--accept-worse", action="store_true")
    parser.add_argument("--accept-temp", type=float, default=0.08)
    parser.add_argument("--min-elo-gain", type=float, default=1.5)
    parser.add_argument("--min-score-gain", type=float, default=0.01)
    parser.add_argument("--time-bonus-threshold", type=float, default=0.08)

    parser.add_argument("--temp-start", type=float, default=0.35)
    parser.add_argument("--temp-min", type=float, default=0.05)
    parser.add_argument("--temp-decay", type=float, default=12.0)
    parser.add_argument("--spectral-base", type=float, default=0.61)
    parser.add_argument("--mut-frac", type=float, default=0.33)
    parser.add_argument("--mut-frac-min", type=float, default=0.25)
    parser.add_argument("--mut-frac-max", type=float, default=0.45)

    parser.add_argument("--noimp-reheat", type=int, default=10)
    parser.add_argument("--reheat-factor", type=float, default=1.7)

    parser.add_argument("--log-jsonl", type=Path, default=Path("logs/cutechess_tuning.jsonl"))
    parser.add_argument("--iteration-config-dir", type=Path, default=Path("logs/cutechess_configs"))
    parser.add_argument("--build-jar", action="store_true", help="Run mvn package (skip tests) before starting")
    parser.add_argument("--mvn", type=str, default="mvn")
    parser.add_argument("--mvn-extra", nargs=argparse.REMAINDER, default=[])

    args = parser.parse_args()

    project_root = find_project_root(args.project_root or Path.cwd())
    args.tuning_path = Path(os.path.expanduser(str(args.tuning_path))).resolve()
    if not args.tuning_path.exists():
        raise SystemExit(f"Tuning file '{args.tuning_path}' not found")
    if args.build_jar:
        maybe_build_jar(project_root, args.mvn, args.mvn_extra)

    ensure_args(args, project_root)

    optimizer = SeedTuningOptimizer(args.tuning_path)
    rng = random.Random(args.seed or int(time.time()))

    base_lines, base_params = optimizer.load()
    best_content = list(base_lines)

    # Run the baseline match and adapt the opponent Elo until the implied rating
    # converges. Without this the first candidate would be compared against a
    # baseline that was achieved versus a weaker opponent, effectively
    # overstating the attainable Elo gain.
    while True:
        baseline = run_match(args, args.tuning_path)
        print("Baseline:", baseline.summary())
        adapt_opponent_elo(args, baseline, context="post-baseline")
        if baseline.opponent_elo == args.opponent_elo:
            break
        print(
            "[baseline] Opponent Elo increased; rerunning baseline at",
            args.opponent_elo,
        )

    best_result = baseline
    current_lines = list(base_lines)
    best_checkpoint = optimizer.checkpoint_best(best_content)
    print(f"Best checkpoint saved to: {best_checkpoint}")

    iteration = 0
    no_improve = 0
    temp_boost = 1.0
    mut_frac_state = args.mut_frac
    mut_frac_floor = args.mut_frac_min
    mut_frac_ceiling = args.mut_frac_max

    try:
        while True:
            iteration += 1
            if args.max_iters and iteration > args.max_iters:
                print("Reached max iterations; exiting loop.")
                break

            print(f"\n=== Iteration {iteration} ===")
            temp_start = args.temp_start * temp_boost
            effective_mut_frac = _effective_mut_frac(mut_frac_state, mut_frac_floor, mut_frac_ceiling, no_improve)
            print(f"[mut] Effective mutation fraction: {effective_mut_frac:.4f}")

            current_lines, current_params = optimizer.load()
            candidate_lines = optimizer.perturb(
                current_lines,
                current_params,
                iteration,
                rng,
                temp_start=temp_start,
                temp_min=args.temp_min,
                temp_decay=args.temp_decay,
                spectral_base=args.spectral_base,
                mut_frac=effective_mut_frac,
                clamp_mult=5.0,
            )
            optimizer.write(candidate_lines)

            try:
                candidate_result = run_match(args, args.tuning_path)
            except Exception as exc:
                print(f"Match execution failed: {exc}. Reverting to best checkpoint.")
                optimizer.write(best_content)
                log_jsonl(
                    args.log_jsonl,
                    {
                        "ts": timestamp(),
                        "iter": iteration,
                        "event": "match_failed",
                        "error": str(exc),
                    },
                )
                no_improve += 1
                temp_boost *= args.reheat_factor
                continue

            print("Candidate:", candidate_result.summary())
            snapshot_path = save_iteration_config(
                args.iteration_config_dir,
                iteration,
                candidate_result,
                candidate_lines,
            )
            print(f"Saved iteration config: {snapshot_path}")

            decision = accept_match_candidate(
                best=best_result,
                candidate=candidate_result,
                temperature=args.accept_temp,
                allow_worse=args.accept_worse,
                rng=rng,
                min_elo_gain=args.min_elo_gain,
                min_score_gain=args.min_score_gain,
                time_bonus_threshold=args.time_bonus_threshold,
            )

            pseudo_best = PseudoTestResult(best_result.total_games, best_result.losses, 0, best_result.duration_s)
            pseudo_cand = PseudoTestResult(candidate_result.total_games, candidate_result.losses, 0, candidate_result.duration_s)
            scale_summary = optimizer.update_step_scales(decision, pseudo_best, pseudo_cand)
            if scale_summary.get("grow", {}).get("count", 0) or scale_summary.get("shrink", {}).get("count", 0):
                print("[step-scale]", json_dumps(scale_summary))

            if decision.keep:
                best_content = list(candidate_lines)
                optimizer.write(best_content)
                if decision.improved:
                    print(f"[keep] Improvement accepted via {decision.reason} ({decision.info})")
                    best_result = candidate_result
                    best_checkpoint = optimizer.checkpoint_best(best_content)
                    print(f"New best checkpoint saved to: {best_checkpoint}")
                    no_improve = 0
                    temp_boost = 1.0
                else:
                    print(f"[keep] Accepted by annealing ({decision.info})")
                    no_improve += 1
                    temp_boost *= args.reheat_factor
                mut_frac_state = _mut_frac_next(
                    mut_frac_state,
                    decision,
                    pseudo_cand,
                    pseudo_best,
                    no_improve,
                    mut_frac_floor,
                    mut_frac_ceiling,
                )
            else:
                print(f"[reject] {decision.reason} ({decision.info})")
                optimizer.write(best_content)
                no_improve += 1
                temp_boost *= args.reheat_factor
                mut_frac_state = _mut_frac_next(
                    mut_frac_state,
                    decision,
                    pseudo_cand,
                    pseudo_best,
                    no_improve,
                    mut_frac_floor,
                    mut_frac_ceiling,
                )

            log_jsonl(
                args.log_jsonl,
                {
                    "ts": timestamp(),
                    "iter": iteration,
                    "decision": dataclasses.asdict(decision),
                    "candidate": {
                        "wins": candidate_result.wins,
                        "losses": candidate_result.losses,
                        "draws": candidate_result.draws,
                        "points_fraction": candidate_result.points_fraction,
                        "elo_diff": candidate_result.elo_diff,
                        "implied_rating": candidate_result.implied_rating,
                        "avg_time_per_game": candidate_result.avg_time_per_game,
                        "duration_s": candidate_result.duration_s,
                    },
                    "best": {
                        "wins": best_result.wins,
                        "losses": best_result.losses,
                        "draws": best_result.draws,
                        "points_fraction": best_result.points_fraction,
                        "elo_diff": best_result.elo_diff,
                        "implied_rating": best_result.implied_rating,
                        "avg_time_per_game": best_result.avg_time_per_game,
                        "duration_s": best_result.duration_s,
                    },
                    "mut_frac_state": mut_frac_state,
                    "no_improve": no_improve,
                    "temp_boost": temp_boost,
                },
            )

            adapt_opponent_elo(args, candidate_result, context=f"post-iteration-{iteration:03d}")

    except KeyboardInterrupt:
        print("\nStopping tuning loop on user request.")
    finally:
        optimizer.write(best_content)
        print("Final best:", best_result.summary())
        print("Done.")


if __name__ == "__main__":
    main()
