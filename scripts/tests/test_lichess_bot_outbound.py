"""Outbound challenges: time controls rotate towards the least played one, Lichess lookups are cached (rate
limits), unanswered challenges are cancelled. The bot script is loaded without starting it."""
import importlib
import sys
import time
from pathlib import Path

import pytest

PY_DIR = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "py"
if str(PY_DIR) not in sys.path:
    sys.path.insert(0, str(PY_DIR))


@pytest.fixture(scope="module")
def bot():
    return importlib.import_module("lichess_bot")


def test_time_controls_parse_and_classify(bot):
    tcs = bot.parse_outbound_tcs("bullet:120+1, blitz:180+2,rapid:600+5")
    assert tcs == [("bullet", 120, 1), ("blitz", 180, 2), ("rapid", 600, 5)]
    # Lichess' estimate (limit + 40 * increment) puts each into the perf it is meant for
    assert [bot._estimate_tc_bucket(lim, inc) for _, lim, inc in tcs] == ["bullet", "blitz", "rapid"]


def test_least_played_time_control_comes_up_most(bot):
    tcs = [("bullet", 120, 1), ("blitz", 180, 2), ("rapid", 600, 5)]
    w = bot.outbound_tc_weights({"blitz": 14, "bullet": 1}, tcs)
    assert w[2] > w[0] > w[1] > 0  # rapid (none) > bullet (1) > blitz (14), but blitz is not starved


def test_lookups_are_cached(bot, monkeypatch):
    calls = []

    class Users:
        def get_public_data(self, name):
            calls.append("me")
            return {"id": name, "perfs": {"blitz": {"rating": 2500}}}

        def get_by_id(self, *ids):
            calls.append(("users", ids))
            return [{"id": i, "perfs": {"rapid": {"rating": 2400}}} for i in ids]

    class Bots:
        def get_online_bots(self):
            calls.append("bots")
            return [{"id": "a", "perfs": {"blitz": {"rating": 2450}}}, {"id": "b"}]

    class Client:
        users = Users()
        bots = Bots()

    monkeypatch.setattr(bot, "API_REQUEST_DELAY", 0)
    monkeypatch.setattr(bot, "svc", None)
    cache = bot.LichessCache(Client(), "alieknek")
    assert bot.find_similar_bots(cache, "alieknek", "blitz") == ["a"]
    assert bot.find_similar_bots(cache, "alieknek", "blitz", exclude=["a"]) == []
    assert calls.count("me") == 1 and calls.count("bots") == 1  # the second search asked Lichess nothing new
    # a bot without the perf in the online list is looked up once, then served from the cache
    assert bot.find_similar_bots(cache, "alieknek", "rapid") == []  # my rapid rating 1500 vs 2400: too far
    bot.find_similar_bots(cache, "alieknek", "rapid")
    looked_up = [uid for c in calls if isinstance(c, tuple) for uid in c[1]]
    assert sorted(looked_up) == ["a", "b"]  # every bot at most once


def test_challenge_returns_its_id_for_cancelling(bot):
    class Challenges:
        def create(self, opponent, **kwargs):
            return {"id": "XyZ123", "url": "https://lichess.org/XyZ123"}

    class Client:
        challenges = Challenges()

    sent, leave_alone, cid = bot.challenge_user(Client(), "somebot", True, 120, 1)
    assert sent and cid == "XyZ123" and leave_alone == bot.OUTBOUND_COOLDOWN_SEC


def test_recent_games_by_speed(tmp_path, monkeypatch):
    svc = importlib.import_module("alieknek_service")
    now = int(time.time() * 1000)
    monkeypatch.setattr(svc, "_archive", {"games": {
        "g1": {"endedAt": now - 3600_000, "speed": "blitz"},
        "g2": {"endedAt": now - 7200_000, "speed": "blitz"},
        "g3": {"endedAt": now - 1800_000, "speed": "bullet"},
        "g4": {"endedAt": now - 30 * 3600_000, "speed": "rapid"},  # older than a day
    }, "opponents": {}, "experience": {}, "replies": {}, "ours": {}, "version": 0})
    assert svc.recent_games_by_speed(24) == {"blitz": 2, "bullet": 1}
