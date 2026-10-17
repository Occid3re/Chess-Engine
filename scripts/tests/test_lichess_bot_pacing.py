"""The bot's in-game calls must never wait behind the global pacing: a move posted two seconds late is a
lost bullet game. These tests load the bot script without starting it."""
import importlib
import sys
from pathlib import Path

import pytest
import requests

PY_DIR = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "py"
if str(PY_DIR) not in sys.path:
    sys.path.insert(0, str(PY_DIR))


@pytest.fixture(scope="module")
def bot():
    return importlib.import_module("lichess_bot")


def test_in_game_requests_are_recognised(bot):
    assert bot._is_in_game_request(("POST", "https://lichess.org/api/bot/game/abc123/move/e2e4"), {})
    assert bot._is_in_game_request(("POST", "https://lichess.org/api/bot/game/abc123/abort"), {})
    assert bot._is_in_game_request(("POST", "https://lichess.org/api/bot/game/abc123/resign"), {})
    assert bot._is_in_game_request((), {"url": "https://lichess.org/api/bot/game/abc123/chat"})
    assert not bot._is_in_game_request(("GET", "https://lichess.org/api/bot/game/stream/abc123"), {})
    assert not bot._is_in_game_request(("GET", "https://lichess.org/api/user/someone"), {})
    assert not bot._is_in_game_request(("POST", "https://lichess.org/api/challenge/someone"), {})


def test_moves_skip_the_limiter_and_other_calls_do_not(bot, monkeypatch):
    calls = []

    class Response:
        ok = True
        status_code = 200

    def fake_request(self, *args, **kwargs):
        calls.append((args, kwargs))
        return Response()

    monkeypatch.setattr(requests.Session, "request", fake_request)
    waited = []
    monkeypatch.setattr(bot._GLOBAL_RL, "wait", lambda: waited.append(True))
    session = bot.make_hardened_session("token")

    session.request("POST", "https://lichess.org/api/bot/game/abc123/move/e2e4")
    assert waited == [], "a move must not wait for the global pacing"
    assert calls[-1][1]["timeout"] == bot.IN_GAME_TIMEOUT

    session.request("GET", "https://lichess.org/api/user/someone")
    assert waited == [True], "ordinary calls keep the pacing"
    assert calls[-1][1]["timeout"] == bot.HTTP_TIMEOUT


def test_running_games_get_the_lean_adapter(bot):
    session = bot.make_hardened_session("token")
    fast = session.get_adapter("https://lichess.org/api/bot/game/abc123/move/e2e4")
    slow = session.get_adapter("https://lichess.org/api/user/someone")
    assert fast is not slow
    assert fast.max_retries.total == 1
    assert fast.max_retries.read == 0
    assert not fast.max_retries.status_forcelist
    assert fast.max_retries.respect_retry_after_header is False
    # The hardened adapter backs off through the wrapper, never by sleeping inside urllib3.
    assert 429 not in slow.max_retries.status_forcelist
    assert slow.max_retries.respect_retry_after_header is False


def test_lag_meter_floors_caps_and_smooths(bot):
    lag = bot._LagMeter()
    assert lag.slack(0.1) == 0.1
    lag.record(0.3)
    assert lag.slack(0.1) == pytest.approx(0.5)
    lag.record(0.3)
    assert lag.slack(0.1) == pytest.approx(0.5)
    lag.record(0.02)
    assert 0.1 <= lag.slack(0.1) < 0.5
    for _ in range(10):
        lag.record(9.0)
    assert lag.slack(0.1) == 1.5, "one hiccup cannot starve the search"
    assert lag.slack(2.0) == 2.0, "the per-game base is a floor"
