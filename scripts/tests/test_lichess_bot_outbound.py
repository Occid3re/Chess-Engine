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


def test_chat_lines_are_short_friendly_and_never_analysis(bot):
    for line in bot.ALIEN_GREETINGS + [l for v in bot.ALIEN_GOODBYES.values() for l in v]:
        assert len(line) <= 140 and not any(w in line.lower() for w in ("blunder", "mistake", "eval", "+0."))
    bye = bot.goodbye_line("win", 12_400_000)
    assert bye.startswith(tuple(bot.ALIEN_GOODBYES["win"])) and "12 million positions" in bye
    assert "million" not in bot.goodbye_line("loss", 50_000)  # small numbers are not worth a mention


def test_chat_is_sent_in_the_background_and_can_be_switched_off(bot, monkeypatch):
    sent = []

    class Bots:
        def post_message(self, game_id, text, spectator=False):
            sent.append((game_id, text, spectator))

    class Client:
        bots = Bots()

    bot.send_chat(Client(), "abcd1234", "hello")
    for _ in range(50):
        if sent:
            break
        time.sleep(0.02)
    assert sent == [("abcd1234", "hello", False)]
    monkeypatch.setattr(bot, "CHAT_ENABLED", False)
    bot.send_chat(Client(), "abcd1234", "quiet")
    time.sleep(0.1)
    assert len(sent) == 1


def test_refusals_escalate_1_day_7_days_30_days_and_survive_a_restart(bot, tmp_path):
    path = tmp_path / "outbound-blocks.json"
    blocks = bot.OutboundBlocks(path)
    assert blocks.strike("SomeBot", "no answer") == 24 * 3600
    assert blocks.strike("somebot", "declined: later") == 7 * 86400
    assert blocks.strike("somebot", "429") == 30 * 86400
    assert blocks.strike("somebot", "no answer") == 30 * 86400  # stays at a month
    again = bot.OutboundBlocks(path)  # after a restart / deploy
    assert again.blocked() == ["somebot"] and again.data["somebot"]["strikes"] == 4
    again.clear("SomeBot")  # a game was played after all
    assert again.blocked() == [] and bot.OutboundBlocks(path).blocked() == []


def test_blocked_bots_are_not_challenged(bot, monkeypatch):
    class Users:
        def get_public_data(self, name):
            return {"id": name, "perfs": {"blitz": {"rating": 2500}}}

    class Bots:
        def get_online_bots(self):
            return [{"id": "a", "perfs": {"blitz": {"rating": 2450}}}, {"id": "b", "perfs": {"blitz": {"rating": 2480}}}]

    class Client:
        users = Users()
        bots = Bots()

    monkeypatch.setattr(bot, "API_REQUEST_DELAY", 0)
    monkeypatch.setattr(bot, "svc", None)
    cache = bot.LichessCache(Client(), "alieknek")
    assert bot.find_similar_bots(cache, "alieknek", "blitz", exclude=["b"]) == ["a"]
    assert bot.find_similar_bots(cache, "alieknek", "blitz", exclude=["a", "b"]) == []


def test_the_app_sees_blocks_and_can_lift_them(bot, tmp_path):
    blocks = bot.OutboundBlocks(tmp_path / "outbound-blocks.json")
    blocks.strike("slowbot", "no answer")
    blocks.strike("rudebot", "declined: generic")
    blocks.strike("rudebot", "declined: generic")
    rows = blocks.snapshot()
    assert [r["id"] for r in rows] == ["slowbot", "rudebot"]  # soonest to expire first
    assert rows[1]["strikes"] == 2 and rows[1]["until"] > time.time() * 1000 + 6 * 86400_000
    assert blocks.unblock("RudeBot") and not blocks.unblock("rudebot")
    assert [r["id"] for r in bot.OutboundBlocks(tmp_path / "outbound-blocks.json").snapshot()] == ["slowbot"]


def test_service_reports_the_outbound_loop_and_unblocks(tmp_path, monkeypatch):
    svc = importlib.import_module("alieknek_service")
    monkeypatch.setattr(svc, "_outbound", {"activity": "starting", "since": None, "until": None, "target": None,
                                           "tc": None, "lastChallengeAt": None, "history": []})
    monkeypatch.setattr(svc, "_outbound_hooks", {"blocks": None, "unblock": None})
    assert svc._outbound_view()["blocks"] == [] and not svc.unblock("somebot")  # no list registered yet

    lifted = []
    svc.register_outbound_blocks(lambda: [{"id": "somebot", "strikes": 1, "until": 1, "reason": "no answer"}],
                                 lambda b: lifted.append(b) or b == "somebot")
    svc.outbound_update("waiting", until=time.time() + 45, target="somebot", tc="blitz 3+2")
    svc.outbound_event("challenged", "somebot", "blitz 3+2")
    view = svc._outbound_view()
    assert view["activity"] == "waiting" and view["target"] == "somebot" and view["until"] > view["since"]
    assert view["lastChallengeAt"] and view["history"][0]["kind"] == "challenged"
    assert view["blocks"][0]["id"] == "somebot"

    assert not svc.unblock("../etc") and lifted == []  # ids are validated before the bot sees them
    assert svc.unblock("somebot") and svc._outbound_view()["history"][0]["kind"] == "unblocked"
    assert not svc.unblock("otherbot")
    for i in range(20):
        svc.outbound_event("no-target")
    assert len(svc._outbound_view()["history"]) == 15
