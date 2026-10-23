"""The run loop reconnects after stream drops. Only drops in a row may add up to the retry limit: a bot that
plays all day sees a routine drop every few hours and must not exit after a few days. These tests load the bot
script without starting it."""
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


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


def runner(clock, uptimes, error=requests.exceptions.ChunkedEncodingError):
    """A run_bot stand-in that stays up for the next uptime, then fails; returns cleanly once they are used up."""
    calls = []

    def run():
        calls.append(clock.now)
        if len(calls) > len(uptimes):
            return
        clock.now += uptimes[len(calls) - 1]
        raise error("Response ended prematurely")

    return run, calls


def test_quick_drops_in_a_row_end_after_the_limit(bot, capsys):
    clock, waits = Clock(), []
    run, calls = runner(clock, [1.0] * 100)

    bot.run_with_reconnect(run, sleep=waits.append, clock=clock)

    assert len(calls) == bot.RECONNECT_MAX_RETRIES
    assert waits == [min(30, 5 * n) for n in range(1, bot.RECONNECT_MAX_RETRIES + 1)]
    assert "Max retries (50) exhausted" in capsys.readouterr().out


def test_a_stable_run_starts_the_count_again(bot, capsys):
    clock, waits = Clock(), []
    stable = bot.RECONNECT_STABLE_SEC
    # Three days of a drop every few hours (more than 50 in all), then a short outage, then a clean exit.
    uptimes = [stable + 3600.0] * 60 + [1.0] * 5
    run, calls = runner(clock, uptimes)

    bot.run_with_reconnect(run, sleep=waits.append, clock=clock)

    out = capsys.readouterr().out
    assert len(calls) == len(uptimes) + 1
    assert "exhausted" not in out
    assert out.count("(attempt 1/50)") == 60
    assert waits[-5:] == [10, 15, 20, 25, 30]


def test_unexpected_errors_wait_longer_and_count_the_same_way(bot, capsys):
    clock, waits = Clock(), []
    run, calls = runner(clock, [1.0] * 7 + [bot.RECONNECT_STABLE_SEC], error=RuntimeError)

    bot.run_with_reconnect(run, sleep=waits.append, clock=clock)

    assert waits == [10, 20, 30, 40, 50, 60, 60, 10]
    assert "Unexpected error (attempt 1/50)" in capsys.readouterr().out.splitlines()[-2]


def test_a_clean_exit_does_not_wait(bot):
    clock, waits = Clock(), []
    run, calls = runner(clock, [])

    bot.run_with_reconnect(run, sleep=waits.append, clock=clock)

    assert len(calls) == 1
    assert waits == []
