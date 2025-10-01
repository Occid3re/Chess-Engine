#!/usr/bin/env python3
"""
Automated tuning loop for Chess-Engine BestMoveSearchTest.

The script repeatedly runs BestMoveSearchTest, perturbs the numeric tuning
parameters in seed-tunings.yaml using a temperature-controlled spectral search,
and keeps the new parameters only when the number of successful test cases
increases. The loop continues until the user terminates it (Ctrl+C).
"""
from __future__ import annotations

import hashlib
import math
import random
import re
import subprocess
import sys
import textwrap
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

# Path to the tuning file we mutate.
TUNING_PATH = Path("src/main/resources/tuning/seed-tunings.yaml")

# Maven command line that enables preview features on Java 21 so the project
# compiles and runs the tests successfully.
MAVEN_BASE_CMD = [
    "mvn",
    "-Djava.version=21",
    "-Dmaven.compiler.source=21",
    "-Dmaven.compiler.target=21",
    "-Dmaven.compiler.enablePreview=true",
    "-DargLine=--enable-preview",
    "-Dtest=BestMoveSearchTest",
    "test",
]

TEST_SUMMARY_PATTERN = re.compile(
    r"Tests run: (?P<total>\d+), Failures: (?P<failures>\d+), Errors: (?P<errors>\d+), Skipped: (?P<skipped>\d+)"
)

NUMERIC_VALUE_PATTERN = re.compile(r"(?P<key>[A-Za-z0-9_.]+):\s*(?P<value>-?\d+(?:\.\d+)?)\s*$")


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

    @property
    def successes(self) -> int:
        return self.total - self.failures - self.errors

    def summary(self) -> str:
        return f"Tests run: {self.total}, successes: {self.successes}, failures: {self.failures}, errors: {self.errors}, skipped: {self.skipped}"


class SeedTuningOptimizer:
    """Encapsulates loading, mutating, and writing the tuning parameters."""

    def __init__(self, tuning_path: Path) -> None:
        self.tuning_path = tuning_path
        if not tuning_path.exists():
            raise FileNotFoundError(f"Cannot find tuning file at {tuning_path}")

    def load(self) -> Tuple[List[str], Dict[str, NumericParameter]]:
        text = self.tuning_path.read_text(encoding="utf-8")
        lines = text.splitlines()

        numeric_parameters: Dict[str, NumericParameter] = {}
        in_numeric_block = False
        base_indent: str | None = None

        for idx, line in enumerate(lines):
            stripped = line.strip()
            if not in_numeric_block:
                if stripped.startswith("numericParameters:"):
                    in_numeric_block = True
                    base_indent = line[: len(line) - len(line.lstrip())]
                continue

            indent = line[: len(line) - len(line.lstrip())]
            if base_indent is not None and len(indent) <= len(base_indent) and stripped:
                # We've reached the end of the numeric parameter block.
                break

            match = NUMERIC_VALUE_PATTERN.match(stripped)
            if match:
                key = match.group("key")
                value = match.group("value")
                numeric_parameters[key] = NumericParameter(
                    name=key,
                    line_index=idx,
                    indent=indent,
                    raw_value=value,
                )

        if not numeric_parameters:
            raise ValueError("No numeric parameters found in seed-tunings.yaml")

        return lines, numeric_parameters

    def write(self, lines: List[str]) -> None:
        content = "\n".join(lines)
        if not content.endswith("\n"):
            content += "\n"
        self.tuning_path.write_text(content, encoding="utf-8")

    def perturb(self, lines: List[str], parameters: Dict[str, NumericParameter], iteration: int, rng: random.Random) -> List[str]:
        """Return a new list of lines with adjusted numeric parameter values."""
        updated_lines = list(lines)

        temperature = max(0.05, 0.35 * math.exp(-iteration / 12.0))
        spectral_frequency = 0.61 + 0.07 * math.sin(iteration * 0.173)

        for param in parameters.values():
            value = param.numeric_value
            magnitude = max(abs(value), 1.0)
            phase = self._phase_from_name(param.name)

            spectral_component = math.sin(iteration * spectral_frequency + phase)
            carrier_component = math.cos(iteration * 0.37 - phase / 2)
            gaussian_component = rng.gauss(0.0, 0.5)

            logistic_gain = 1.0 / (1.0 + math.exp(-value / (50.0 + magnitude)))
            blend = 0.6 * spectral_component + 0.3 * carrier_component + 0.1 * gaussian_component
            delta = temperature * logistic_gain * blend

            candidate = value + delta * magnitude

            if param.is_float:
                formatted = f"{candidate:.{param.decimals}f}"
            else:
                formatted = str(int(round(candidate)))

            updated_lines[param.line_index] = f"{param.indent}{param.name}: {formatted}"

        return updated_lines

    @staticmethod
    def _phase_from_name(name: str) -> float:
        digest = hashlib.sha256(name.encode("utf-8")).digest()
        integer = int.from_bytes(digest[:8], byteorder="big", signed=False)
        return (integer % 1000) / 1000.0 * 2.0 * math.pi


def run_best_move_tests() -> TestResult:
    """Execute the Maven test command and parse the aggregated results."""
    process = subprocess.run(
        MAVEN_BASE_CMD,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )

    stdout = process.stdout
    stderr = process.stderr
    matches = TEST_SUMMARY_PATTERN.findall(stdout)
    if not matches:
        raise RuntimeError(
            "Failed to parse test summary from Maven output.\n" +
            textwrap.shorten(stdout or stderr, width=500)
        )

    last_match = matches[-1]
    total, failures, errors, skipped = map(int, last_match)
    return TestResult(total=total, failures=failures, errors=errors, skipped=skipped, stdout=stdout, stderr=stderr)


def main() -> None:
    optimizer = SeedTuningOptimizer(TUNING_PATH)
    rng = random.Random()
    rng.seed(time.time_ns())

    print("Running initial BestMoveSearchTest suite...")
    baseline_result = run_best_move_tests()
    print("Baseline:", baseline_result.summary())

    best_lines, _ = optimizer.load()
    best_content = list(best_lines)
    best_result = baseline_result
    iteration = 0

    try:
        while True:
            iteration += 1
            print(f"\n=== Iteration {iteration} ===")
            current_lines, current_parameters = optimizer.load()
            candidate_lines = optimizer.perturb(current_lines, current_parameters, iteration, rng)
            optimizer.write(candidate_lines)

            try:
                candidate_result = run_best_move_tests()
            except Exception as exc:  # noqa: BLE001 - we want to revert on any failure
                print(f"Test execution failed: {exc}. Reverting changes.")
                optimizer.write(best_content)
                continue

            print("Candidate:", candidate_result.summary())

            if candidate_result.successes > best_result.successes:
                print("Improvement detected. Keeping new tuning parameters.")
                best_content = list(candidate_lines)
                best_result = candidate_result
            else:
                print("No improvement. Reverting to previous best parameters.")
                optimizer.write(best_content)
    except KeyboardInterrupt:
        print("\nStopping optimization loop by user request.")
        optimizer.write(best_content)
        print("Final best result:", best_result.summary())


if __name__ == "__main__":
    try:
        main()
    except Exception as err:  # noqa: BLE001
        print(f"Fatal error: {err}", file=sys.stderr)
        sys.exit(1)
