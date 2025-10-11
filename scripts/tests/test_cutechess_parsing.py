import math

import pytest

from scripts.cutechess_tuning_loop import MatchResult, parse_score


def _parse(stdout: str, engine_name: str = "YourJava", opponent_name: str = "SF2000") -> MatchResult:
    return parse_score(
        stdout=stdout,
        engine_name=engine_name,
        opponent_name=opponent_name,
        opponent_elo=2000.0,
        duration_s=12.34,
        command=["cutechess-cli"],
        stderr="",
    )


def test_parse_score_engine_first_orientation():
    stdout = """
    Started game 1 of 4 (YourJava vs SF2000)
    Finished game 1 (YourJava vs SF2000): 1-0 {White mates}
    Score of YourJava vs SF2000: 1 - 0 - 0  [1.000] 1
    Finished game 2 (SF2000 vs YourJava): 1-0 {White mates}
    Score of YourJava vs SF2000: 1 - 1 - 0  [0.500] 2
    Finished game 3 (YourJava vs SF2000): 1/2-1/2 {Draw}
    Score of YourJava vs SF2000: 1 - 1 - 1  [0.500] 3
    Finished game 4 (SF2000 vs YourJava): 0-1 {Black mates}
    Score of YourJava vs SF2000: 2 - 1 - 1  [0.625] 4
    """.strip()

    result = _parse(stdout)

    assert result.wins == 2
    assert result.losses == 1
    assert result.draws == 1
    assert math.isclose(result.points_fraction, 0.625)


def test_parse_score_opponent_first_orientation():
    stdout = """
    Started game 1 of 10 (SF2000 vs YourJava)
    Started game 2 of 10 (YourJava vs SF2000)
    Finished game 2 (YourJava vs SF2000): 0-1 {Black mates}
    Score of SF2000 vs YourJava: 1 - 0 - 0  [1.000] 1
    Finished game 1 (SF2000 vs YourJava): 1-0 {White mates}
    Score of SF2000 vs YourJava: 2 - 0 - 0  [1.000] 2
    Finished game 10 (YourJava vs SF2000): 1-0 {White mates}
    Score of SF2000 vs YourJava: 8 - 2 - 0  [0.800] 10
    """.strip()

    result = _parse(stdout)

    assert result.wins == 2
    assert result.losses == 8
    assert result.draws == 0
    assert math.isclose(result.points_fraction, 0.2)


def test_parse_score_missing_summary_raises():
    with pytest.raises(ValueError):
        _parse("Started game 1 (YourJava vs SF2000)")
