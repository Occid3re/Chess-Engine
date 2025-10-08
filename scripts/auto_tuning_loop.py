#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Automated tuning loop for Chess-Engine BestMoveSearchTest (advanced, JVM-adaptive).

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

JVM & Engine parity with lichess_bot
------------------------------------
- Auto CPU plan -> searchThreads, lazySmpThreads, rootParallelLimit.
- ZGC by default (override via --jvm-gc).
- -XX:ActiveProcessorCount (planned CPUs), -XX:ConcGCThreads, -XX:CICompilerCount.
- -Xms/-Xmx auto (based on RAM & TT size) or explicit via flags.
- Pass engine system props into the test JVM:
    -Dchessengine.searchThreads, -Dchessengine.lazySmpThreads,
    -Dchessengine.rootParallelLimit, -Dchessengine.tt.mb, -Dchessengine.tuning.file, etc.
- All of the above are placed into Surefire's forked JVM via -DargLine="...".

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
  --jvm-gc zgc `
  --tt-mb 1024 `
  --engine-threads auto `
  --lazy-threads auto `
  --root-par-limit auto `
  --xms auto `
  --xmx auto `
  --extra-maven-args -q

If the test lives in a submodule:
  --extra-maven-args -pl :engine -am

python .\scripts\auto_tuning_loop.py --project-root C:\Development\Chess-Engine --mvn .\mvnw.cmd --test julius.game.chessengine.ai.BestMoveSearchTest --java-release 25 --preview --accept-worse --accept-temp 0.08 --mut-frac 0.30 --noimp-reheat 12 --reheat-factor 1.7 --jvm-gc zgc --tt-mb 1024 --engine-threads auto --lazy-threads auto --root-par-limit auto --xms auto --xmx auto --plan-concurrent 1 --extra-maven-args -q
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
from typing import Dict, Iterable, List, Optional, Tuple

# ----------------------------
# Patterns
# ----------------------------

TEST_SUMMARY_PATTERN = re.compile(
    r"Tests run: (?P<total>\d+), Failures: (?P<failures>\d+), Errors: (?P<errors>\d+), Skipped: (?P<skipped>\d+)"
)

NUMERIC_VALUE_PATTERN = re.compile(
    r"(?P<key>[A-Za-z0-9_.]+):\s*(?P<value>-?\d+(?:\.\d+)?)\s*$"
)

BMSTAT_PATTERN = re.compile(r"\[BMSTAT\]\s*(\{.*\})")
BMSUM_PATTERN  = re.compile(r"\[BMSUM\]\s*(\{.*\})")

PARAM_DECL_PATTERN = re.compile(
    r'"(?P<key>[^"]+)",\s*(?P<default>-?[0-9_\.]+)(?:,\s*(?P<min>-?[0-9_\.]+),\s*(?P<max>-?[0-9_\.]+))?\)'
)

# ----------------------------
# Data classes
# ----------------------------

@dataclass
class NumericParameter:
    name: str
    line_index: int
    indent: str
    yaml_key: str
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
    metrics: Dict[str, float] = dataclasses.field(default_factory=dict)
    decision_stats: List[Dict] = dataclasses.field(default_factory=list)

    @property
    def successes(self) -> int:
        return self.total - self.failures - self.errors

    def summary(self) -> str:
        base = (
            f"Tests run: {self.total}, successes: {self.successes}, "
            f"failures: {self.failures}, errors: {self.errors}, skipped: {self.skipped}, "
            f"duration: {self.duration_s:.2f}s"
        )
        extras: List[str] = []
        avg_cp_loss = self.metrics.get("avgCpLoss") if self.metrics else None
        if avg_cp_loss is not None:
            extras.append(f"avgCpLoss: {avg_cp_loss:.2f} cp")
        top1_rate = self.metrics.get("top1Rate") if self.metrics else None
        if top1_rate is not None:
            extras.append(f"top1Rate: {top1_rate * 100:.1f}%")
        avg_nodes = self.metrics.get("avgNodes") if self.metrics else None
        if avg_nodes is not None:
            extras.append(f"avgNodes: {avg_nodes:.0f}")
        avg_duration = self.metrics.get("avgDurationMs") if self.metrics else None
        if avg_duration is not None:
            extras.append(f"avgDuration: {avg_duration:.1f} ms")
        if extras:
            base += " | " + ", ".join(extras)
        return base

    def score_tuple(self) -> Tuple[float, float, float, float, float, float]:
        # higher is better for successes/top1Rate; lower better for failures/errors/cpLoss/duration
        top1_rate = float(self.metrics.get("top1Rate", 0.0) or 0.0)
        avg_cp_loss = float(self.metrics.get("avgCpLoss", 0.0) or 0.0)
        return (
            float(self.successes),
            float(-self.failures),
            float(-self.errors),
            top1_rate,
            -avg_cp_loss,
            -self.duration_s,
        )


@dataclass(frozen=True)
class ParameterConstraint:
    key: str
    default: float
    minimum: Optional[float]
    maximum: Optional[float]

    def contains(self, value: float) -> bool:
        if value is None:
            return False
        if self.minimum is not None and value < self.minimum - 1e-12:
            return False
        if self.maximum is not None and value > self.maximum + 1e-12:
            return False
        return True

    @property
    def span(self) -> Optional[float]:
        if self.minimum is None or self.maximum is None:
            return None
        return self.maximum - self.minimum


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


def _parse_java_number(token: Optional[str]) -> Optional[float]:
    if token is None:
        return None
    cleaned = token.strip().replace("_", "")
    if not cleaned:
        return None
    try:
        return float(cleaned)
    except ValueError:
        return None


def _normalize_param_key(key: str) -> str:
    trimmed = key.strip()
    if not trimmed:
        return trimmed
    needs_lower = any(ch.lower() != ch for ch in trimmed)
    return trimmed.lower() if needs_lower else trimmed

def timestamp() -> str:
    return time.strftime("%Y%m%d-%H%M%S", time.localtime())

# ----------------------------
# CPU/RAM planning (parity with lichess_bot philosophy)
# ----------------------------

def _detect_cpus() -> Tuple[int, int]:
    logical = os.cpu_count() or 2
    physical = None
    try:
        import psutil  # optional
        physical = psutil.cpu_count(logical=False) or None
    except Exception:
        pass
    if physical is None:
        physical = max(1, logical // 2)
    return logical, physical

def _total_memory_bytes() -> Optional[int]:
    try:
        import psutil
        return int(psutil.virtual_memory().total)
    except Exception:
        # Windows fallback via environ (not reliable), else None
        return None

def _auto_thread_plan(max_concurrent_games: int = 1) -> Dict[str, int]:
    logical, physical = _detect_cpus()
    reserve = 2 + max(0, max_concurrent_games - 1)
    target_total = max(1, min(physical, (logical - reserve)))
    search_threads = min(3, max(1, target_total // 4))
    lazy_threads = max(1, target_total - search_threads)
    root_par_limit = max(24, min(96, search_threads * 12))
    return {
        "logical": logical,
        "physical": physical,
        "search": search_threads,
        "lazy": lazy_threads,
        "root_limit": root_par_limit,
        "planned_total": max(1, search_threads + lazy_threads),
    }

def _gc_flag(gc: str) -> str:
    g = (gc or "").lower()
    if g == "zgc":
        return "-XX:+UseZGC"
    if g == "shenandoah":
        return "-XX:+UseShenandoahGC"
    if g == "g1":
        return "-XX:+UseG1GC"
    # allow custom flag pass-through
    return gc

def _derive_jvm_flags(
        jvm_gc: str,
        xms: str,
        xmx: str,
        apc: int,
        conc_gc_threads: Optional[int],
        ci_compiler_count: Optional[int],
        soft_max: Optional[str] = None,
) -> List[str]:
    flags = [
        _gc_flag(jvm_gc),
        f"-Xms{xms}",
        f"-Xmx{xmx}",
        "-XX:+AlwaysPreTouch",
        "-XX:FlightRecorderOptions=stackdepth=256",
        f"-XX:ActiveProcessorCount={apc}",
    ]
    if conc_gc_threads is not None:
        flags.append(f"-XX:ConcGCThreads={conc_gc_threads}")
    if ci_compiler_count is not None:
        flags.append(f"-XX:CICompilerCount={ci_compiler_count}")
    if soft_max:
        flags.append(f"-XX:SoftMaxHeapSize={soft_max}")
    # pleasant ZGC tuning
    if jvm_gc.lower() == "zgc":
        flags.append("-XX:ZUncommitDelay=60")
    return flags

def _auto_heap(tt_mb: int) -> Tuple[str, str, Optional[str]]:
    """
    Simple heuristic:
      - base = max(2048MB, 3 * TT)
      - cap at 75% of RAM if psutil available
    """
    base_mb = max(2048, 3 * max(1, tt_mb))
    ram = _total_memory_bytes()
    soft = None
    if ram:
        ram_mb = ram // (1024 * 1024)
        hard_cap = max(1024, int(ram_mb * 0.75))
        base_mb = min(base_mb, hard_cap)
        # optional soft cap to let ZGC uncommit under memory pressure
        soft = f"{max(1024, int(ram_mb * 0.5))}m"
    return f"{base_mb}m", f"{base_mb}m", soft

# ----------------------------
# Optimizer
# ----------------------------

class SeedTuningOptimizer:
    """Encapsulates loading, mutating, and writing the tuning parameters."""

    def __init__(self, tuning_path: Path) -> None:
        self.tuning_path = tuning_path
        if not tuning_path.exists():
            raise FileNotFoundError(f"Cannot find tuning file at {tuning_path}")
        self.project_root = find_project_root(tuning_path.parent)
        self._initial_magnitudes: Dict[str, float] = {}
        self._constraints: Dict[str, ParameterConstraint] = self._load_param_constraints()
        self._reported_missing = False

    def backup(self, suffix: str) -> Path:
        dst = self.tuning_path.with_suffix(self.tuning_path.suffix + f".{suffix}.{timestamp()}.bak")
        shutil.copy2(self.tuning_path, dst)
        return dst

    def load(self) -> Tuple[List[str], Dict[str, NumericParameter]]:
        text = self.tuning_path.read_text(encoding="utf-8")
        lines = text.splitlines()

        numeric_parameters = self._extract_numeric_parameters(lines)
        evaluation_parameters = self._extract_evaluation_parameters(lines)

        combined: Dict[str, NumericParameter] = {}
        combined.update(evaluation_parameters)
        combined.update(numeric_parameters)

        if not combined:
            raise ValueError(
                "No tunable parameters found in seed-tunings.yaml (expected evaluation.modules.* or numericParameters entries)."
            )

        self._report_missing_parameters(combined.keys())

        return lines, combined

    def _load_param_constraints(self) -> Dict[str, ParameterConstraint]:
        constraints: Dict[str, ParameterConstraint] = {}
        param_file = self.project_root / "src" / "main" / "java" / "julius" / "game" / "chessengine" / "tuning" / "ParamId.java"
        if not param_file.exists():
            return constraints
        try:
            text = param_file.read_text(encoding="utf-8")
        except OSError:
            return constraints

        for match in PARAM_DECL_PATTERN.finditer(text):
            key = match.group("key")
            default = _parse_java_number(match.group("default"))
            min_value = _parse_java_number(match.group("min"))
            max_value = _parse_java_number(match.group("max"))
            if key is None or default is None:
                continue
            normalized = _normalize_param_key(key)
            constraints[normalized] = ParameterConstraint(
                key=normalized,
                default=default,
                minimum=min_value,
                maximum=max_value,
            )
        return constraints

    def _report_missing_parameters(self, parameter_names: Iterable[str]) -> None:
        if self._reported_missing or not self._constraints:
            return
        normalized_present = {_normalize_param_key(name) for name in parameter_names}
        missing = sorted(k for k in self._constraints if k not in normalized_present)
        if missing:
            print("⚠️  Missing ParamId entries in seed-tunings.yaml:")
            for key in missing:
                print(f"   - {key}")
        self._reported_missing = True

    def _compute_initial_magnitude(
            self,
            value: float,
            constraint: Optional[ParameterConstraint],
            is_float: bool,
    ) -> float:
        base = max(abs(value), 1.0)
        if is_float:
            base = max(base, 0.1)
        if constraint:
            span = constraint.span
            if span is not None and span > 0:
                base = max(base, span * 0.05)
            else:
                distances: List[float] = []
                if constraint.minimum is not None:
                    distances.append(abs(value - constraint.minimum))
                if constraint.maximum is not None:
                    distances.append(abs(constraint.maximum - value))
                if distances:
                    base = max(base, max(distances) * 0.5)
        return base

    def _maybe_choose_special_target(
            self,
            current_value: float,
            constraint: Optional[ParameterConstraint],
            rng: random.Random,
            is_float: bool,
    ) -> Optional[float]:
        if not constraint:
            return None
        sentinels: List[float] = []
        for sentinel in (-1.0, 0.0):
            if constraint.contains(sentinel):
                if is_float:
                    if not math.isclose(current_value, sentinel, abs_tol=0.05):
                        sentinels.append(sentinel)
                else:
                    if int(round(current_value)) != int(round(sentinel)):
                        sentinels.append(sentinel)
        if not sentinels:
            return None
        if rng.random() < 0.12:
            return rng.choice(sentinels)
        return None

    def _apply_bounds(
            self,
            candidate: float,
            constraint: Optional[ParameterConstraint],
            is_float: bool,
    ) -> float:
        if not constraint:
            return candidate
        if constraint.minimum is not None and candidate < constraint.minimum:
            candidate = constraint.minimum
        if constraint.maximum is not None and candidate > constraint.maximum:
            candidate = constraint.maximum
        if not is_float:
            candidate = float(int(round(candidate)))
        return candidate

    def _extract_numeric_parameters(self, lines: List[str]) -> Dict[str, NumericParameter]:
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
                    yaml_key=key,
                    raw_value=value,
                )

        return numeric_parameters

    def _extract_evaluation_parameters(self, lines: List[str]) -> Dict[str, NumericParameter]:
        evaluation_parameters: Dict[str, NumericParameter] = {}
        in_evaluation = False
        evaluation_indent: Optional[int] = None
        in_modules = False
        modules_indent: Optional[int] = None
        current_module: Optional[str] = None
        module_indent: Optional[int] = None

        for idx, line in enumerate(lines):
            stripped = line.strip()
            if not stripped:
                continue

            indent_len = len(line) - len(line.lstrip())

            if not in_evaluation:
                if stripped.startswith("evaluation:"):
                    in_evaluation = True
                    evaluation_indent = indent_len
                continue

            if evaluation_indent is not None and indent_len <= evaluation_indent:
                in_evaluation = False
                in_modules = False
                current_module = None
                continue

            if not in_modules:
                if stripped.startswith("modules:"):
                    in_modules = True
                    modules_indent = indent_len
                continue

            if modules_indent is not None and indent_len <= modules_indent:
                in_modules = False
                current_module = None
                continue

            if stripped.endswith(":") and ":" not in stripped[:-1]:
                current_module = stripped[:-1].strip()
                module_indent = indent_len
                continue

            if current_module and module_indent is not None and indent_len > module_indent:
                match = NUMERIC_VALUE_PATTERN.match(stripped)
                if match:
                    key = match.group("key")
                    value = match.group("value")
                    full_key = f"evaluation.modules.{current_module}.{key}"
                    evaluation_parameters[full_key] = NumericParameter(
                        name=full_key,
                        line_index=idx,
                        indent=line[: len(line) - len(line.lstrip())],
                        yaml_key=key,
                        raw_value=value,
                    )

        return evaluation_parameters

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
        """Adjust a random subset of numeric parameters."""
        updated_lines = list(lines)

        temperature = max(temp_min, temp_start * math.exp(-iteration / max(1e-9, temp_decay)))
        spectral_frequency = spectral_base + 0.07 * math.sin(iteration * 0.173)

        keys = list(parameters.keys())
        if not keys:
            return updated_lines

        k = max(1, int(round(len(keys) * max(0.0, min(1.0, mut_frac)))))
        mutate_keys = set(rng.sample(keys, k))

        for name, param in parameters.items():
            value = param.numeric_value

            constraint = self._constraints.get(_normalize_param_key(name))

            if name not in self._initial_magnitudes:
                self._initial_magnitudes[name] = self._compute_initial_magnitude(value, constraint, param.is_float)
            init_mag = self._initial_magnitudes[name]

            if name not in mutate_keys:
                updated_lines[param.line_index] = f"{param.indent}{param.yaml_key}: {param.raw_value}"
                continue

            magnitude = max(abs(value), init_mag)
            phase = self._phase_from_name(name)

            spectral_component = math.sin(iteration * spectral_frequency + phase)
            carrier_component  = math.cos(iteration * 0.37 - phase / 2.0)
            gaussian_component = rng.gauss(0.0, 0.5)

            logistic_gain = 1.0 / (1.0 + math.exp(-value / (50.0 + magnitude)))
            blend = 0.6 * spectral_component + 0.3 * carrier_component + 0.1 * gaussian_component
            delta = temperature * logistic_gain * blend

            special_target = self._maybe_choose_special_target(value, constraint, rng, param.is_float)
            if special_target is not None:
                candidate = special_target
            else:
                candidate = value + delta * magnitude

            lo = -clamp_mult * init_mag
            hi =  clamp_mult * init_mag
            if constraint:
                if constraint.minimum is not None:
                    lo = min(lo, constraint.minimum)
                if constraint.maximum is not None:
                    hi = max(hi, constraint.maximum)
            if candidate < lo:
                candidate = lo + 0.1 * (rng.random())
            elif candidate > hi:
                candidate = hi - 0.1 * (rng.random())

            candidate = self._apply_bounds(candidate, constraint, param.is_float)

            if param.is_float:
                formatted = f"{candidate:.{param.decimals}f}"
            else:
                formatted = str(int(round(candidate)))

            updated_lines[param.line_index] = f"{param.indent}{param.yaml_key}: {formatted}"
            param.raw_value = formatted

        return updated_lines

# ----------------------------
# Maven test runner (with JVM/system props parity)
# ----------------------------

def _aggregate_test_totals_from_reports(project_root: Path) -> Tuple[int, int, int, int]:
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


def _parse_best_move_metrics(stdout: str, stderr: str) -> Tuple[List[Dict], Dict]:
    records: List[Dict] = []
    summary: Dict = {}
    for stream in (stdout, stderr):
        if not stream:
            continue
        for line in stream.splitlines():
            stat_match = BMSTAT_PATTERN.search(line)
            if stat_match:
                try:
                    records.append(json.loads(stat_match.group(1)))
                except json.JSONDecodeError:
                    pass
            sum_match = BMSUM_PATTERN.search(line)
            if sum_match:
                try:
                    summary = json.loads(sum_match.group(1))
                except json.JSONDecodeError:
                    pass
    summary = {k: v for k, v in summary.items() if v is not None}
    return records, summary


def _compose_argline(
        use_preview: bool,
        jvm_gc: str,
        xms: str,
        xmx: str,
        apc: int,
        conc_gc_threads: Optional[int],
        ci_compiler_count: Optional[int],
        soft_max: Optional[str],
        engine_sysprops: Dict[str, str],
) -> str:
    parts: List[str] = []
    if use_preview:
        parts.append("--enable-preview")
    parts.extend(_derive_jvm_flags(jvm_gc, xms, xmx, apc, conc_gc_threads, ci_compiler_count, soft_max))
    for k, v in engine_sysprops.items():
        parts.append(f"-D{k}={v}")
    return " ".join(parts)


def run_best_move_tests(
        project_root: Path,
        mvn_bin: str,
        test_name: str,
        java_release: int,
        use_preview: bool,
        extra_maven_args: List[str],
        # JVM & engine parity inputs:
        jvm_gc: str,
        xms: str,
        xmx: str,
        apc: int,
        conc_gc_threads: Optional[int],
        ci_compiler_count: Optional[int],
        soft_max: Optional[str],
        engine_sysprops: Dict[str, str],
) -> TestResult:
    """
    Execute Maven tests and parse results.
      - Puts JVM flags + engine system properties into Surefire's fork via -DargLine="...".
    """
    arg_line_str = _compose_argline(
        use_preview, jvm_gc, xms, xmx, apc, conc_gc_threads, ci_compiler_count, soft_max, engine_sysprops
    )

    base_cmd = [
        mvn_bin,
        f"-Djava.version={java_release}",
        f"-Dmaven.compiler.source={java_release}",
        f"-Dmaven.compiler.target={java_release}",
        f"-Dmaven.compiler.enablePreview={'true' if use_preview else 'false'}",
        f"-DargLine={arg_line_str}",
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
    decision_stats, summary_metrics = _parse_best_move_metrics(stdout, stderr)

    matches = TEST_SUMMARY_PATTERN.findall(stdout) or TEST_SUMMARY_PATTERN.findall(stderr)
    if matches:
        total, failures, errors, skipped = map(int, matches[-1])
        return TestResult(
            total,
            failures,
            errors,
            skipped,
            stdout,
            stderr,
            dt,
            metrics=summary_metrics,
            decision_stats=decision_stats,
        )

    try:
        total, failures, errors, skipped = _aggregate_test_totals_from_reports(project_root)
        return TestResult(
            total,
            failures,
            errors,
            skipped,
            stdout,
            stderr,
            dt,
            metrics=summary_metrics,
            decision_stats=decision_stats,
        )
    except Exception as ex:
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

def score_tuple(res: TestResult) -> Tuple[float, float, float, float, float, float]:
    return res.score_tuple()

def tuple_better(
        a: Tuple[float, float, float, float, float, float],
        b: Tuple[float, float, float, float, float, float],
) -> bool:
    return a > b

def scalar_score(res: TestResult) -> float:
    avg_cp_loss = float(res.metrics.get("avgCpLoss", 0.0) or 0.0)
    top1_rate = float(res.metrics.get("top1Rate", 0.0) or 0.0)
    return (
        (res.successes * 1.0)
        - (res.failures * 0.6)
        - (res.errors * 1.5)
        - (0.02 * res.duration_s)
        - (0.005 * avg_cp_loss)
        + (0.5 * top1_rate)
    )

def accept_candidate(best: TestResult, cand: TestResult, temp: float, allow_worse: bool, rng: random.Random) -> bool:
    bt = score_tuple(best)
    ct = score_tuple(cand)
    if tuple_better(ct, bt):
        return True
    if not allow_worse:
        return False
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
    parser.add_argument("--tuning-path", type=Path, default=Path("src/main/resources/tuning/seed-tunings.yaml"))
    parser.add_argument("--project-root", type=Path, default=None)
    parser.add_argument("--mvn", type=str, default="mvn")
    parser.add_argument("--test", type=str, default="BestMoveSearchTest")
    parser.add_argument("--java-release", type=int, default=25)
    parser.add_argument("--preview", action="store_true", default=True)
    parser.add_argument("--no-preview", dest="preview", action="store_false")
    parser.add_argument("--extra-maven-args", nargs=argparse.REMAINDER, default=[])

    parser.add_argument("--max-iters", type=int, default=0)
    parser.add_argument("--seed", type=int, default=None)

    # Exploration knobs
    parser.add_argument("--temp-start", type=float, default=0.35)
    parser.add_argument("--temp-min",   type=float, default=0.05)
    parser.add_argument("--temp-decay", type=float, default=12.0)
    parser.add_argument("--spectral-base", type=float, default=0.61)

    parser.add_argument("--accept-worse", action="store_true")
    parser.add_argument("--accept-temp",  type=float, default=0.08)
    parser.add_argument("--mut-frac",     type=float, default=0.33)
    parser.add_argument("--noimp-reheat", type=int,   default=10)
    parser.add_argument("--reheat-factor",type=float, default=1.7)

    parser.add_argument("--log-jsonl", type=Path, default=Path("logs/auto_tuning_log.jsonl"))
    parser.add_argument("--git-commit", action="store_true")

    # -------- JVM & Engine parity options (mirroring lichess_bot) --------
    parser.add_argument("--jvm-gc", type=str, default=os.environ.get("JAVA_GC", "zgc"),
                        help='GC: zgc|shenandoah|g1 or a custom JVM flag.')
    parser.add_argument("--xms", type=str, default=os.environ.get("JAVA_XMS", "auto"),
                        help='Heap Xms (e.g., 6g, 4096m) or "auto".')
    parser.add_argument("--xmx", type=str, default=os.environ.get("JAVA_XMX", "auto"),
                        help='Heap Xmx (e.g., 6g, 4096m) or "auto".')
    parser.add_argument("--apc", type=str, default="auto",
                        help='ActiveProcessorCount value or "auto".')
    parser.add_argument("--concgc", type=str, default="auto",
                        help='ConcGCThreads value or "auto".')
    parser.add_argument("--cicomp", type=str, default="auto",
                        help='CICompilerCount value or "auto".')

    parser.add_argument("--tt-mb", type=int, default=int(os.environ.get("CHESSENGINE_TT_MB", "1024")))
    parser.add_argument("--engine-threads", type=str, default=os.environ.get("CHESSENGINE_THREADS", "auto"))
    parser.add_argument("--lazy-threads", type=str, default=os.environ.get("CHESSENGINE_LAZY_THREADS", "auto"))
    parser.add_argument("--root-par-limit", type=str, default=os.environ.get("CHESSENGINE_ROOT_PAR_LIMIT", "auto"))
    parser.add_argument("--tuning-file", type=str, default=os.environ.get("CHESSENGINE_TUNING_FILE", ""),
                        help="Optional absolute path to tuning file passed as -Dchessengine.tuning.file.")

    # A hint for planning: how many games concurrently during tests (kept 1)
    parser.add_argument("--plan-concurrent", type=int, default=1)

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

    # ---------- Plan parallelism (parity with lichess_bot)
    plan = _auto_thread_plan(max_concurrent_games=max(1, args.plan_concurrent))
    def _resolve_int(opt: str, auto_val: int) -> int:
        return auto_val if str(opt).strip().lower() == "auto" else int(opt)

    search_threads = _resolve_int(args.engine_threads, plan["search"]) if str(args.engine_threads).isdigit() or args.engine_threads == "auto" else int(args.engine_threads)
    lazy_threads   = _resolve_int(args.lazy_threads,   plan["lazy"])   if str(args.lazy_threads).isdigit()   or args.lazy_threads   == "auto" else int(args.lazy_threads)
    root_limit     = _resolve_int(args.root_par_limit, plan["root_limit"]) if str(args.root_par_limit).isdigit() or args.root_par_limit == "auto" else int(args.root_par_limit)

    planned_total  = max(1, search_threads + lazy_threads)
    apc_val        = _resolve_int(args.apc, plan["planned_total"]) if args.apc == "auto" else int(args.apc)

    # GC helpers (keep conservative; scale modestly)
    logical, physical = _detect_cpus()
    conc_gc_threads = None if args.concgc == "auto" else int(args.concgc)
    ci_comp_count   = None if args.cicomp == "auto" else int(args.cicomp)
    if args.concgc == "auto":
        conc_gc_threads = max(1, min(4, apc_val // 2))
    if args.cicomp == "auto":
        ci_comp_count = max(1, min(8, apc_val))

    # Heap sizing
    if args.xms.lower() == "auto" or args.xmx.lower() == "auto":
        xms_auto, xmx_auto, soft_max = _auto_heap(args.tt_mb)
        xms = xms_auto if args.xms.lower() == "auto" else args.xms
        xmx = xmx_auto if args.xmx.lower() == "auto" else args.xmx
    else:
        xms = args.xms
        xmx = args.xmx
        soft_max = None

    print(
        f"[plan] CPUs(logical/physical)={logical}/{physical} | "
        f"search={search_threads} lazy={lazy_threads} rootParallelLimit={root_limit} | "
        f"APC={apc_val} ConcGCThreads={conc_gc_threads} CICompilerCount={ci_comp_count} | "
        f"Xms={xms} Xmx={xmx} GC={args.jvm_gc}"
    )

    # Engine system properties (mirroring the bot defaults)
    engine_sysprops = {
        "chessengine.searchThreads": str(search_threads),
        "chessengine.lazySmpThreads": str(lazy_threads),
        "chessengine.rootParallelLimit": str(root_limit),
        "chessengine.tt.mb": str(args.tt_mb),
        "logging.level.root": "INFO",
        # Keep UCI verbosity modest in tests
        "chessengine.uci.info.minIntervalMs": "200",
        "chessengine.uci.info.maxPvLen": "10",
    }
    if args.tuning_file:
        engine_sysprops["chessengine.tuning.file"] = args.tuning_file

    # ---------- Initial backup & load
    optimizer.backup("initial")
    best_lines, _ = optimizer.load()
    best_content = list(best_lines)

    # ---------- Baseline
    print("\nRunning initial test suite…")
    baseline_result = run_best_move_tests(
        project_root=project_root,
        mvn_bin=mvn_bin,
        test_name=args.test,
        java_release=args.java_release,
        use_preview=args.preview,
        extra_maven_args=extra_args,
        jvm_gc=args.jvm_gc,
        xms=xms,
        xmx=xmx,
        apc=apc_val,
        conc_gc_threads=conc_gc_threads,
        ci_compiler_count=ci_comp_count,
        soft_max=soft_max,
        engine_sysprops=engine_sysprops,
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

            current_lines, current_parameters = optimizer.load()
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
                    jvm_gc=args.jvm_gc,
                    xms=xms,
                    xmx=xmx,
                    apc=apc_val,
                    conc_gc_threads=conc_gc_threads,
                    ci_compiler_count=ci_comp_count,
                    soft_max=soft_max,
                    engine_sysprops=engine_sysprops,
                )
            except Exception as exc:
                print(f"Test execution failed: {exc}. Reverting changes.")
                optimizer.write(best_content)
                log_jsonl(
                    args.log_jsonl,
                    {"ts": timestamp(), "iter": iteration, "event": "test_execution_failed", "error": str(exc)},
                )
                continue

            print("Candidate  :", candidate_result.summary())

            keep = accept_candidate(
                best=best_result,
                cand=candidate_result,
                temp=args.accept_temp,
                allow_worse=args.accept_worse,
                rng=rng,
            )

            if keep:
                improved = tuple_better(score_tuple(candidate_result), score_tuple(best_result))
                print("Improvement detected. Keeping new tuning parameters."
                      if improved else
                      "Accepted worse candidate via annealing. Keeping new tuning parameters.")
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
                            f"({args.test}, iter {iteration}, {'improvement' if improved else 'annealed'})"
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
                    print(f"(Plateau) Reheating: temp_start ×= {args.reheat_factor:.2f} "
                          f"-> {args.temp_start * temp_boost:.4f}")

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
        optimizer.write(best_content)
        print("Final best :", best_result.summary())
        print("Done.")

if __name__ == "__main__":
    try:
        main()
    except Exception as err:
        print(f"Fatal error: {err}", file=sys.stderr)
        sys.exit(1)
