"""Tests for scripts/tuning: the cutechess result parser (moved from the former cutechess_tuning_loop.py),
the Elo conversion, and that spsa.py reads exactly the tunables core/Tune.java declares."""
import math
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tuning"))
from cutechess import elo_from_score, parse_score  # noqa: E402
import spsa  # noqa: E402


def test_parse_score_engine_first_orientation():
    stdout = """
    Started game 1 of 4 (plus vs minus)
    Finished game 1 (plus vs minus): 1-0 {White mates}
    Finished game 2 (minus vs plus): 1-0 {White mates}
    Finished game 3 (plus vs minus): 1/2-1/2 {Draw}
    Finished game 4 (minus vs plus): 0-1 {Black mates}
    Score of plus vs minus: 2 - 1 - 1  [0.625] 4
    """
    result = parse_score(stdout, "plus", "minus")
    assert (result.wins, result.losses, result.draws) == (2, 1, 1)
    assert math.isclose(result.points_fraction, 0.625)


def test_parse_score_opponent_first_and_sprt_verdict():
    stdout = """
    Finished game 2 (child vs parent): 0-1 {Black mates}
    Finished game 1 (parent vs child): 1-0 {White mates}
    Finished game 10 (child vs parent): 1-0 {White mates}
    SPRT: llr -2.96 (-100.5%), lbound -2.94, ubound 2.94 - H0 was accepted
    """
    result = parse_score(stdout, "child", "parent")
    assert (result.wins, result.losses, result.draws) == (1, 2, 0)
    assert result.verdict == "H0"


def test_parse_score_summary_fallback_and_missing():
    result = parse_score("Score of parent vs child: 8 - 2 - 0  [0.800] 10", "child", "parent")
    assert (result.wins, result.losses) == (2, 8)
    with pytest.raises(ValueError):
        parse_score("Started game 1 (a vs b)", "a", "b")


def test_elo_from_score():
    assert elo_from_score(0.5) == pytest.approx(0.0)
    assert elo_from_score(0.64) == pytest.approx(100.0, abs=1.0)
    assert elo_from_score(0.0) < -1000 and elo_from_score(1.0) > 1000


def test_spsa_reads_the_engine_tunables():
    params = spsa.read_params()
    names = [p["name"] for p in params]
    assert "rfpMargin" in names and "lmrBase" in names
    assert len(names) == len(set(names))
    for p in params:
        assert p["min"] <= p["default"] <= p["max"]
        assert p["c"] > 0


def test_spsa_schedules_shrink_and_end_at_c():
    c_first, a_first = spsa.schedules(1, 800, 10.0, 0.002)
    c_last, a_last = spsa.schedules(800, 800, 10.0, 0.002)
    assert c_first > c_last == pytest.approx(10.0)
    assert a_first > a_last
