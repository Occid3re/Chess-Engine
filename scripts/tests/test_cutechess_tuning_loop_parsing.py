"""Tests for cutechess_tuning_loop parsing helpers."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest


# Ensure the scripts directory (parent of this file) is importable.
SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))


from cutechess_tuning_loop import MatchResult, parse_score  # type: ignore  # noqa: E402


def build_stdout(summary_line: str) -> str:
    return "\n".join(
        [
            "Started game 1 of 10 (SF2000 vs YourJava)",
            "Finished game 1 (SF2000 vs YourJava): 1-0 {White mates}",
            summary_line,
            "Finished match",
        ]
    )


def test_parse_score_handles_engine_as_first_participant() -> None:
    stdout = build_stdout("Score of YourJava vs SF2000: 2 - 8 - 0  [0.200] 10")
    result = parse_score(stdout, "YourJava", "SF2000", 2000.0, 10.0, ("cutechess",), "")
    assert isinstance(result, MatchResult)
    assert result.wins == 2
    assert result.losses == 8
    assert result.draws == 0
    assert result.score == pytest.approx(0.2)


def test_parse_score_inverts_when_engine_listed_second() -> None:
    stdout = build_stdout("Score of SF2000 vs YourJava: 8 - 2 - 0  [0.800] 10")
    result = parse_score(stdout, "YourJava", "SF2000", 2000.0, 10.0, ("cutechess",), "")
    assert isinstance(result, MatchResult)
    assert result.wins == 2
    assert result.losses == 8
    assert result.draws == 0
    assert result.score == pytest.approx(0.2)

