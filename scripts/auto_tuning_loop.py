#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Automated tuning loop for Chess-Engine BestMoveSearchTest (advanced).

Highlights
----------
- Project-root autodetect (or --project-root override).
- Atomic writes, timestamped backups, and "best" checkpoint file.
- Temperature-controlled spectral search + simulated annealing to escape plateaus.
- Mutate *subset* of parameters per iteration (less thrashing), soft clamping.
- Reheating after repeated non-improvements.
- Robust Maven execution:
    * Pass-through extra args (no PowerShell quoting pain).
    * Explicit surefire:test goal.
    * Aggregates Surefire/Failsafe XML across *all* modules recursively.
    * Dumps stdout/stderr to logs/ on failure.
- Clear acceptance priority (successes ↑, failures ↓, errors ↓, duration ↓).
- JSONL logging per iteration; optional Git commit on improvement.

Typical usage (PowerShell)
--------------------------
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --java-release 25 `
  --preview `
  --accept-worse `
  --accept-temp 0.08 `
  --mut-frac 0.30 `
  --noimp-reheat 12 `
  --reheat-factor 1.7 `
  --extra-maven-args -q

If the test lives in a submodule:
  --extra-maven-args -pl :engine -am
"""

from __future__ import annotations

import argparse
import dataclasses
import glob
import hashlib
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# ----------------------------
# Patterns
# ----------------------------

TEST_SUMMARY_PATTERN = re.compile(
    r"Tests run: (?P<total>\d+), Failures: (?P<failures>\d+), Errors: (?P<errors>\d+), Skipped: (?P<skipped>\d+)"
)

NUMERIC_VALUE_PATTERN = re.compile(
    r"(?P<key>[A-Za-z0-9_.]+):\s*(?P<value>-?\d+(?:\.\d+)?)\s*$"
)

# ----------------------------
# Data classes
# ----------------------------

@dataclass
class NumericParameter:
    name: str
    line_index: int
    indent: str
    raw_value: str

    @property
    def is_float(self) -> bool:
        return "." in self.raw_value

    @property
    def decimals(self) -> int:
        if "." not in self.raw_value:
            return 0
        return len(self.raw_value.split(".")[1])

    @property
    def numeric_value(self) -> float:
        return float(self.raw_value)


@dataclass
class TestResult:
    total: int
    failures: int
    errors: int
    skipped: int
    stdout: str
    stderr: str
    duration_s: float

    @property
    def successes(self) -> int:
        return self.total - self.failures - self.errors

    def summary(self) -> str:
        return (
            f"Tests run: {self.total}, successes: {self.successes}, "
            f"failures: {self.failures}, errors: {self.errors}, skipped: {self.skipped}, "
            f"duration: {self.duration_s:.2f}s"
        )

    def score_tuple(self) -> Tuple[int, int, int, float]:
        # higher is better for successes; lower better for failures/errors/duration
        return (self.successes, -self.failures, -self.errors, -self.duration_s)

# ----------------------------
# Utilities
# ----------------------------

def find_project_root(start: Path) -> Path:
    """Find the nearest ancestor directory containing a pom.xml."""
    cur = start.resolve()
    if cur.is_file():
        cur = cur.parent
    for path in [cur, *cur.parents]:
        if (path / "pom.xml").exists():
            return path
    return start

def atomic_write(path: Path, content: str) -> None:
    tmp_fd, tmp_path = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    try:
        with os.fdopen(tmp_fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(content if content.endswith("\n") else content + "\n")
        os.replace(tmp_path, path)
    finally:
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except Exception:
            pass

def timestamp() -> str:
    return time.strftime("%Y%m%d-%H%M%S", time.localtime())

# ----------------------------
# Optimizer
# ----------------------------

class SeedTuningOptimizer:
    """Encapsulates loading, mutating, and writing the tuning parameters."""

    def __init__(self, tuning_path: Path) -> None:
        self.tuning_path = tuning_path
        if not tuning_path.exists():
            raise FileNotFoundError(f"Cannot find tuning file at {tuning_path}")
        # track initial magnitudes for soft clamping
        self._initial_magnitudes: Dict[str, float] = {}

    def backup(self, suffix: str) -> Path:
        dst = self.tuning_path.with_suffix(self.tuning_path.suffix + f".{suffix}.{timestamp()}.bak")
        shutil.copy2(self.tuning_path, dst)
        return dst

    def load(self) -> Tuple[List[str], Dict[str, NumericParameter]]:
        text = self.tuning_path.read_text(encoding="utf-8")
        lines = text.splitlines()

        numeric_parameters: Dict[str, NumericParameter] = {}
        in_numeric_block = False
        base_indent_len: Optional[int] = None

        for idx, line in enumerate(lines):
            stripped = line.strip()

            if not in_numeric_block:
                if stripped.startswith("numericParameters:"):
                    in_numeric_block = True
                    base_indent_len = len(line) - len(line.lstrip())
                continue

            # Detect end of block: a non-empty line whose indent is <= base indent
            indent_len = len(line) - len(line.lstrip())
            if stripped and base_indent_len is not None and indent_len <= base_indent_len:
                break

            match = NUMERIC_VALUE_PATTERN.match(stripped)
            if match:
                key = match.group("key")
                value = match.group("value")
                numeric_parameters[key] = NumericParameter(
                    name=key,
                    line_index=idx,
                    indent=line[: len(line) - len(line.lstrip())],
                    raw_value=value,
                )

        if not numeric_parameters:
            raise ValueError("No numeric parameters found under 'numericParameters:' in seed-tunings.yaml")

        return lines, numeric_parameters

    def write(self, lines: List[str]) -> None:
        content = "\n".join(lines)
        atomic_write(self.tuning_path, content)

    def checkpoint_best(self, lines: List[str]) -> Path:
        dst = self.tuning_path.with_name(self.tuning_path.stem + ".best" + self.tuning_path.suffix)
        atomic_write(dst, "\n".join(lines))
        return dst

    @staticmethod
    def _phase_from_name(name: str) -> float:
        digest = hashlib.sha256(name.encode("utf-8")).digest()
        integer = int.from_bytes(digest[:8], byteorder="big", signed=False)
        return (integer % 1000) / 1000.0 * 2.0 * math.pi

    def perturb(
            self,
            lines: List[str],
            parameters: Dict[str, NumericParameter],
            iteration: int,
            rng: random.Random,
            temp_start: float,
            temp_min: float,
            temp_decay: float,
            spectral_base: float,
            mut_frac: float = 0.33,
            clamp_mult: float = 5.0,
    ) -> List[str]:
        """
        Adjust a random subset of numeric parameters.
        - mut_frac: fraction of keys to mutate each iteration (0..1)
        - clamp_mult: soft clamp around initial |value| to avoid runaway
        """
        updated_lines = list(lines)

        # Temperature schedule with decay
        temperature = max(temp_min, temp_start * math.exp(-iteration / max(1e-9, temp_decay)))
        # Slight iteration-dependent spectral drift
        spectral_frequency = spectral_base + 0.07 * math.sin(iteration * 0.173)

        keys = list(parameters.keys())
        if not keys:
            return updated_lines

        # choose subset to mutate
        k = max(1, int(round(len(keys) * max(0.0, min(1.0, mut_frac)))))
        mutate_keys = set(rng.sample(keys, k))

        for name, param in parameters.items():
            value = param.numeric_value

            # remember initial magnitudes for clamping
            if name not in self._initial_magnitudes:
                self._initial_magnitudes[name] = max(abs(value), 1.0)
            init_mag = self._initial_magnitudes[name]

            if name not in mutate_keys:
                # leave untouched (preserves param formatting)
                updated_lines[param.line_index] = f"{param.indent}{param.name}: {param.raw_value}"
                continue

            magnitude = max(abs(value), 1.0)
            phase = self._phase_from_name(name)

            spectral_component = math.sin(iteration * spectral_frequency + phase)
            carrier_component  = math.cos(iteration * 0.37 - phase / 2.0)
            gaussian_component = rng.gauss(0.0, 0.5)

            logistic_gain = 1.0 / (1.0 + math.exp(-value / (50.0 + magnitude)))
            blend = 0.6 * spectral_component + 0.3 * carrier_component + 0.1 * gaussian_component
            delta = temperature * logistic_gain * blend

            candidate = value + delta * magnitude

            # soft clamp: keep within ±(clamp_mult * initial magnitude)
            lo = -clamp_mult * init_mag
            hi =  clamp_mult * init_mag
            if candidate < lo:
                candidate = lo + 0.1 * (rng.random())
            elif candidate > hi:
                candidate = hi - 0.1 * (rng.random())

            if param.is_float:
                formatted = f"{candidate:.{param.decimals}f}"
            else:
                formatted = str(int(round(candidate)))

            updated_lines[param.line_index] = f"{param.indent}{param.name}: {formatted}"

        return updated_lines

# ----------------------------
# Maven test runner
# ----------------------------

def _aggregate_test_totals_from_reports(project_root: Path) -> Tuple[int, int, int, int]:
    """
    Recursively scan *all modules* for Surefire/Failsafe XML reports and aggregate totals.

    Looks for:
      **/target/surefire-reports/TEST-*.xml
      **/target/failsafe-reports/TEST-*.xml

    Returns (total, failures, errors, skipped).
    Raises RuntimeError if no report files are found anywhere.
    """
    patterns = [
        str(project_root / "**" / "target" / "surefire-reports" / "TEST-*.xml"),
        str(project_root / "**" / "target" / "failsafe-reports" / "TEST-*.xml"),
    ]
    files: List[str] = []
    for pat in patterns:
        files.extend(glob.glob(pat, recursive=True))

    if not files:
        raise RuntimeError(
            "No test report XMLs found in any module.\n"
            f"  {patterns[0]}\n  {patterns[1]}"
        )

    total = failures = errors = skipped = 0

    def add_suite(suite):
        nonlocal total, failures, errors, skipped
        t  = int(suite.attrib.get("tests", 0))
        fz = int(suite.attrib.get("failures", 0))
        er = int(suite.attrib.get("errors", 0))
        # some schemas use "ignored" instead of "skipped"
        sk = int(suite.attrib.get("skipped", suite.attrib.get("ignored", 0)) or 0)
        total += t; failures += fz; errors += er; skipped += sk

    for f in files:
        try:
            tree = ET.parse(f)
            root = tree.getroot()
            if root.tag == "testsuite":
                add_suite(root)
            else:
                for suite in root.findall(".//testsuite"):
                    add_suite(suite)
        except Exception:
            continue

    return total, failures, errors, skipped


def run_best_move_tests(
        project_root: Path,
        mvn_bin: str,
        test_name: str,
        java_release: int,
        use_preview: bool,
        extra_maven_args: List[str],
) -> TestResult:
    """
    Execute Maven tests and parse results.
    Strategy:
      1) Run the Surefire goal explicitly (module-aware via extra args like -pl/-am).
      2) Parse console summary if present.
      3) Otherwise aggregate all module XML reports (Surefire/Failsafe).
      4) If still nothing, dump stdout/stderr to logs/ and raise.
    """
    arg_line = "--enable-preview" if use_preview else ""
    base_cmd = [
        mvn_bin,
        f"-Djava.version={java_release}",
        f"-Dmaven.compiler.source={java_release}",
        f"-Dmaven.compiler.target={java_release}",
        f"-Dmaven.compiler.enablePreview={'true' if use_preview else 'false'}",
        f"-DargLine={arg_line}",          # picked up by surefire forked JVM
        "-DskipTests=false",
        "-Dsurefire.printSummary=true",
        f"-Dtest={test_name}",
        "surefire:test",
        *extra_maven_args,
    ]

    t0 = time.perf_counter()
    proc = subprocess.run(
        base_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
        cwd=str(project_root),
    )
    dt = time.perf_counter() - t0

    stdout = proc.stdout or ""
    stderr = proc.stderr or ""

    # Prefer console summary when present
    matches = TEST_SUMMARY_PATTERN.findall(stdout) or TEST_SUMMARY_PATTERN.findall(stderr)
    if matches:
        total, failures, errors, skipped = map(int, matches[-1])
        return TestResult(total, failures, errors, skipped, stdout, stderr, dt)

    # Aggregate XMLs across modules (Surefire/Failsafe)
    try:
        total, failures, errors, skipped = _aggregate_test_totals_from_reports(project_root)
        return TestResult(total, failures, errors, skipped, stdout, stderr, dt)
    except Exception as ex:
        # Dump outputs for diagnostics
        logs_dir = project_root / "logs"
        logs_dir.mkdir(parents=True, exist_ok=True)
        out_path = logs_dir / f"maven_stdout_{timestamp()}.log"
        err_path = logs_dir / f"maven_stderr_{timestamp()}.log"
        out_path.write_text(stdout, encoding="utf-8")
        err_path.write_text(stderr, encoding="utf-8")

        shortened = textwrap.shorten((stdout or stderr) or "", width=1000, placeholder=" …")
        raise RuntimeError(
            "Failed to parse test summary from Maven output or any module reports.\n"
            f"Command: {' '.join(base_cmd)}\n"
            f"Output snippet:\n{shortened}\n"
            f"Fallback error: {ex}\n"
            f"Full outputs dumped to:\n  {out_path}\n  {err_path}"
        )

# ----------------------------
# Scoring and acceptance
# ----------------------------

def score_tuple(res: TestResult) -> Tuple[int, int, int, float]:
    return (res.successes, -res.failures, -res.errors, -res.duration_s)

def tuple_better(a: Tuple[int, int, int, float], b: Tuple[int, int, int, float]) -> bool:
    return a > b  # lexicographic

def scalar_score(res: TestResult) -> float:
    # Smooth scalar for annealing decision
    return (res.successes * 1.0) - (res.failures * 0.6) - (res.errors * 1.5) - (0.02 * res.duration_s)

def accept_candidate(best: TestResult, cand: TestResult, temp: float, allow_worse: bool, rng: random.Random) -> bool:
    bt = score_tuple(best)
    ct = score_tuple(cand)
    if tuple_better(ct, bt):
        return True
    if not allow_worse:
        return False
    # Simulated annealing: accept occasionally when worse
    db = scalar_score(cand) - scalar_score(best)  # negative if worse
    prob = math.exp(db / max(1e-9, temp))
    return rng.random() < prob

# ----------------------------
# Logging
# ----------------------------

def log_jsonl(path: Path, record: dict) -> None:
    line = json.dumps(record, ensure_ascii=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(line + "\n")

# ----------------------------
# Main
# ----------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Automated tuning loop for BestMoveSearchTest.")
    parser.add_argument(
        "--tuning-path",
        type=Path,
        default=Path("src/main/resources/tuning/seed-tunings.yaml"),
        help="Path to the YAML file with numericParameters (default: %(default)s)",
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=None,
        help="Project root containing pom.xml. If omitted, auto-detected from tuning path upward.",
    )
    parser.add_argument("--mvn", type=str, default="mvn", help="Maven executable (default: mvn)")
    parser.add_argument("--test", type=str, default="BestMoveSearchTest", help="JUnit test class/pattern (default: %(default)s)")
    parser.add_argument("--java-release", type=int, default=25, help="Java release for source/target (default: %(default)s)")
    parser.add_argument("--preview", action="store_true", default=True, help="Enable Java --enable-preview (default: True)")
    parser.add_argument("--no-preview", dest="preview", action="store_false", help="Disable Java preview features.")
    parser.add_argument(
        "--extra-maven-args",
        nargs=argparse.REMAINDER,
        default=[],
        help="Extra args passed directly to Maven (everything after this flag).",
    )

    parser.add_argument("--max-iters", type=int, default=0, help="Stop after N iterations (0 = infinite until Ctrl+C).")
    parser.add_argument("--seed", type=int, default=None, help="RNG seed (default: time-based).")

    # Temperature / exploration knobs
    parser.add_argument("--temp-start", type=float, default=0.35, help="Initial temperature (default: %(default)s)")
    parser.add_argument("--temp-min",   type=float, default=0.05, help="Minimum temperature (default: %(default)s)")
    parser.add_argument("--temp-decay", type=float, default=12.0, help="e-folding decay constant (default: %(default)s)")
    parser.add_argument("--spectral-base", type=float, default=0.61, help="Base spectral frequency (default: %(default)s)")

    # Advanced search controls
    parser.add_argument("--accept-worse", action="store_true", help="Enable simulated annealing (accept worse occasionally).")
    parser.add_argument("--accept-temp",  type=float, default=0.08, help="Annealing temperature (default: %(default)s).")
    parser.add_argument("--mut-frac",     type=float, default=0.33, help="Fraction of parameters to mutate each iter (default: %(default)s).")
    parser.add_argument("--noimp-reheat", type=int,   default=10,   help="Reheat after N no-improvement iterations (default: %(default)s).")
    parser.add_argument("--reheat-factor",type=float, default=1.7,  help="Temp multiplier when reheating (default: %(default)s).")

    parser.add_argument("--log-jsonl", type=Path, default=Path("logs/auto_tuning_log.jsonl"), help="Path to JSONL log file.")
    parser.add_argument("--git-commit", action="store_true", help="Commit improved tunings via git per improvement.")

    args = parser.parse_args()

    tuning_path: Path  = args.tuning_path
    project_root: Path = args.project_root or find_project_root(tuning_path.parent)
    mvn_bin: str       = args.mvn
    extra_args: List[str] = args.extra_maven_args

    print(f"Project root: {project_root}")
    print(f"Tuning file : {tuning_path}")

    optimizer = SeedTuningOptimizer(tuning_path)

    rng = random.Random()
    if args.seed is None:
        args.seed = time.time_ns() & 0xFFFFFFFF
    rng.seed(args.seed)
    print(f"RNG seed    : {args.seed}")

    # Initial backup & load
    optimizer.backup("initial")
    best_lines, _ = optimizer.load()
    best_content = list(best_lines)

    # Baseline
    print("\nRunning initial test suite…")
    baseline_result = run_best_move_tests(
        project_root=project_root,
        mvn_bin=mvn_bin,
        test_name=args.test,
        java_release=args.java_release,
        use_preview=args.preview,
        extra_maven_args=extra_args,
    )
    print("Baseline   :", baseline_result.summary())

    best_result = baseline_result
    best_checkpoint = optimizer.checkpoint_best(best_content)
    print(f"Best checkpoint saved to: {best_checkpoint}")

    iteration = 0
    no_improve = 0
    temp_boost = 1.0

    try:
        while True:
            iteration += 1
            if args.max_iters and iteration > args.max_iters:
                print("\nReached max iterations; stopping.")
                break

            print(f"\n=== Iteration {iteration} ===")

            # Reload in case of external edits
            current_lines, current_parameters = optimizer.load()

            # Effective temperature (with reheating)
            eff_temp_start = args.temp_start * temp_boost

            candidate_lines = optimizer.perturb(
                current_lines,
                current_parameters,
                iteration,
                rng,
                temp_start=eff_temp_start,
                temp_min=args.temp_min,
                temp_decay=args.temp_decay,
                spectral_base=args.spectral_base,
                mut_frac=args.mut_frac,
                clamp_mult=5.0,
            )
            optimizer.write(candidate_lines)

            try:
                candidate_result = run_best_move_tests(
                    project_root=project_root,
                    mvn_bin=mvn_bin,
                    test_name=args.test,
                    java_release=args.java_release,
                    use_preview=args.preview,
                    extra_maven_args=extra_args,
                )
            except Exception as exc:  # revert on any failure
                print(f"Test execution failed: {exc}. Reverting changes.")
                optimizer.write(best_content)
                log_jsonl(
                    args.log_jsonl,
                    {"ts": timestamp(), "iter": iteration, "event": "test_execution_failed", "error": str(exc)},
                )
                continue

            print("Candidate  :", candidate_result.summary())

            # Decide to keep (improvement or annealed acceptance)
            keep = accept_candidate(
                best=best_result,
                cand=candidate_result,
                temp=args.accept_temp,
                allow_worse=args.accept_worse,
                rng=rng,
            )

            if keep:
                kept_because = (
                    "improvement"
                    if tuple_better(score_tuple(candidate_result), score_tuple(best_result))
                    else "annealed-accept"
                )
                print(
                    "Improvement detected. Keeping new tuning parameters."
                    if kept_because == "improvement"
                    else "Accepted worse candidate via annealing. Keeping new tuning parameters."
                )
                best_content = list(candidate_lines)
                best_result = candidate_result
                best_checkpoint = optimizer.checkpoint_best(best_content)
                print(f"Updated best checkpoint: {best_checkpoint}")
                no_improve = 0

                if args.git_commit:
                    try:
                        msg = (
                            f"Auto-tune: {best_result.successes} ok, "
                            f"{best_result.failures} fail, {best_result.errors} err "
                            f"({args.test}, iter {iteration}, {kept_because})"
                        )
                        subprocess.run(["git", "add", str(tuning_path)], cwd=str(project_root), check=False)
                        subprocess.run(["git", "commit", "-m", msg], cwd=str(project_root), check=False)
                    except Exception as git_exc:
                        print(f"(Non-fatal) Git commit failed: {git_exc}")

            else:
                print("No improvement. Reverting to previous best parameters.")
                optimizer.write(best_content)
                no_improve += 1
                if args.noimp_reheat and (no_improve % args.noimp_reheat == 0):
                    temp_boost *= args.reheat_factor
                    print(f"(Plateau) Reheating: temp_start ×= {args.reheat_factor:.2f} -> {args.temp_start * temp_boost:.4f}")

            # Log iteration
            log_jsonl(
                args.log_jsonl,
                {
                    "ts": timestamp(),
                    "iter": iteration,
                    "seed": args.seed,
                    "temp_start_effective": eff_temp_start,
                    "mut_frac": args.mut_frac,
                    "candidate": dataclasses.asdict(candidate_result),
                    "best": dataclasses.asdict(best_result),
                    "accepted": keep,
                    "no_improve": no_improve,
                },
            )

    except KeyboardInterrupt:
        print("\nStopping optimization loop by user request.")
    finally:
        # Ensure we end with the best content in place
        optimizer.write(best_content)
        print("Final best :", best_result.summary())
        print("Done.")

if __name__ == "__main__":
    try:
        main()
    except Exception as err:
        print(f"Fatal error: {err}", file=sys.stderr)
        sys.exit(1)
