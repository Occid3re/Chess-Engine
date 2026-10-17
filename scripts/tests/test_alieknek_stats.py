"""The play app's numbers: game entries from the Lichess export, the results summary and the rating curve."""
import importlib
import json
import sys
from pathlib import Path

import pytest

PY_DIR = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "py"
if str(PY_DIR) not in sys.path:
    sys.path.insert(0, str(PY_DIR))


@pytest.fixture()
def svc(tmp_path, monkeypatch):
    module = importlib.import_module("alieknek_service")
    monkeypatch.setattr(module, "DATA_DIR", tmp_path)
    monkeypatch.setattr(module, "_history", {})
    monkeypatch.setenv("ALIEKNEK_NAME", "Alieknek")
    return module


def game(result, color="white", perf="blitz", opp=2400, diff=5, rated=True, opening="Sicilian Defense: Najdorf",
         ended=1_000):
    return {"result": result, "color": color, "perf": perf, "opponentRating": opp, "ratingDiff": diff,
            "rated": rated, "opening": opening, "endedAt": ended, "rating": 2480}


def test_game_entry_from_the_export(svc):
    exported = {
        "id": "abcdEFGH", "rated": True, "speed": "blitz", "perf": "blitz", "status": "mate", "winner": "black",
        "players": {"white": {"user": {"id": "somebot", "name": "SomeBot", "title": "BOT"}, "rating": 2501},
                    "black": {"user": {"id": "alieknek", "name": "Alieknek"}, "rating": 2480, "ratingDiff": 6}},
        "clock": {"initial": 180, "increment": 2}, "opening": {"name": "French Defense: Winawer"},
        "moves": "e4 e6 d4 d5", "lastMoveAt": 1234,
    }
    entry = svc._game_entry(exported)
    assert entry["color"] == "black" and entry["result"] == "win"
    assert (entry["opponent"], entry["opponentTitle"], entry["opponentRating"]) == ("SomeBot", "BOT", 2501)
    assert entry["clock"] == {"initial": 180, "increment": 2} and entry["plies"] == 4
    exported["players"]["black"]["user"]["id"] = "stranger"
    assert svc._game_entry(exported) is None


def test_stats_split_and_performance(svc):
    games = [game("win"), game("win", color="black"), game("draw"), game("loss", color="black", diff=-7),
             game("aborted"), game("win", perf="bullet", rated=False, opening="Caro-Kann Defense")]
    stats = svc._stats(games)
    overall = stats["all"]
    assert (overall["games"], overall["wins"], overall["draws"], overall["losses"]) == (5, 3, 1, 1)
    assert overall["score"] == pytest.approx(3.5 / 5)
    # average opponent 2400, (3 - 1) net wins over 5 games -> +160
    assert overall["performance"] == 2560
    # the unrated bullet win does not move the rating
    assert overall["ratingChange"] == 5 + 5 + 5 - 7
    assert stats["white"]["games"] == 3 and stats["black"]["games"] == 2
    assert set(stats["byPerf"]) == {"blitz", "bullet"}
    assert stats["openings"][0]["name"] == "Sicilian Defense" and stats["openings"][0]["games"] == 4


def test_stats_without_games(svc):
    stats = svc._stats([])
    assert stats["all"]["games"] == 0 and stats["all"]["score"] is None and stats["all"]["performance"] is None
    assert stats["openings"] == [] and stats["byPerf"] == {}


def test_history_backfills_then_grows_only_on_change(svc, tmp_path):
    games = [game("win", ended=2_000, diff=6), game("loss", ended=1_000, diff=-4),
             game("draw", rated=False, ended=3_000)]
    svc._record_history({"blitz": {"rating": 2486}}, games)
    points = svc._history["blitz"]
    # two rated games (rating before + change, oldest first), then the current rating
    assert [p[1] for p in points] == [2476, 2486]
    saved = json.loads((tmp_path / "rating-history.json").read_text(encoding="utf-8"))
    assert saved == svc._history

    svc._record_history({"blitz": {"rating": 2486}}, games)
    assert len(svc._history["blitz"]) == 2
    svc._record_history({"blitz": {"rating": 2490}, "bullet": {"rating": 2300}}, games)
    assert svc._history["blitz"][-1][1] == 2490 and svc._history["bullet"][-1][1] == 2300


def test_engine_name_is_reported(svc):
    svc.engine_started("Alieknek 4.0.6")
    assert svc._engine["name"] == "Alieknek 4.0.6"
