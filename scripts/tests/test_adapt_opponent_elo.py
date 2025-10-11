import argparse
import math

from cutechess_tuning_loop import MatchResult, adapt_opponent_elo


def make_result(opponent_elo: int, *, wins: int, losses: int, draws: int) -> MatchResult:
    total = wins + losses + draws
    score = (wins + 0.5 * draws) / total if total else 0.0
    return MatchResult(
        engine_name="YourJava",
        opponent_name="SF",
        wins=wins,
        losses=losses,
        draws=draws,
        score=score,
        opponent_elo=opponent_elo,
        duration_s=10.0,
        stdout="",
        stderr="",
        command=(),
    )


def test_adapt_opponent_elo_never_decreases(capsys):
    args = argparse.Namespace(opponent_elo=2000)
    winning_result = make_result(args.opponent_elo, wins=6, losses=0, draws=0)

    expected_floor = int(math.ceil(winning_result.implied_rating))
    adapt_opponent_elo(args, winning_result, context="baseline")
    first_capture = capsys.readouterr().out

    assert args.opponent_elo == expected_floor
    assert getattr(args, "_opponent_elo_floor") == expected_floor
    assert f"{expected_floor}" in first_capture

    losing_result = make_result(args.opponent_elo, wins=0, losses=6, draws=0)
    adapt_opponent_elo(args, losing_result, context="regression")
    second_capture = capsys.readouterr().out

    assert args.opponent_elo == expected_floor
    assert getattr(args, "_opponent_elo_floor") == expected_floor
    assert f"keeping {expected_floor}" in second_capture
