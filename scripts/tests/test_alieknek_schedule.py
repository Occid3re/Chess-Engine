"""The bot's weekly play schedule: parsing, canonical text and the window arithmetic."""
import datetime
import importlib
import sys
from pathlib import Path

import pytest

PY_DIR = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "py"
if str(PY_DIR) not in sys.path:
    sys.path.insert(0, str(PY_DIR))


@pytest.fixture(scope="module")
def svc():
    return importlib.import_module("alieknek_service")


def test_plain_windows_apply_to_every_day(svc):
    week = svc.parse_schedule("01:00-05:00,13:00-14:00")
    assert week == [[(60, 300), (780, 840)]] * 7
    assert svc.format_schedule(week) == "01:00-05:00,13:00-14:00"


def test_always_and_never(svc):
    assert svc.parse_schedule("") == [[(0, 1440)]] * 7
    assert svc.parse_schedule("always") == [[(0, 1440)]] * 7
    assert svc.parse_schedule("never") == [[]] * 7
    assert svc.format_schedule([[(0, 1440)]] * 7) == "always"
    assert svc.format_schedule([[]] * 7) == "never"


def test_weekday_groups_and_midnight(svc):
    week = svc.parse_schedule("Mo-Fr 01:00-05:00; Sa,So 22:00-02:00")
    assert week[0] == [(0, 120), (60, 300)] or week[0] == [(0, 300)]
    assert week[0] == [(0, 300)], "Sunday's late window spills into Monday and merges with the night"
    assert week[4] == [(60, 300)]
    assert week[5] == [(1320, 1440)]
    assert week[6] == [(0, 120), (1320, 1440)]
    assert svc.format_schedule(week) == "Mo 00:00-05:00; Di-Fr 01:00-05:00; Sa 22:00-24:00; So 00:00-02:00,22:00-24:00"
    # English names and ranges over the week's end work too; days not named do not play.
    week = svc.parse_schedule("sat-mon 10:00-11:00")
    assert [bool(d) for d in week] == [True, False, False, False, False, True, True]


def test_nonsense_is_refused(svc):
    for bad in ("Mo", "Xx 01:00-05:00", "25:00-26:00", "01:00", "Mo-Fr"):
        with pytest.raises(ValueError):
            svc.parse_schedule(bad)


def test_schedule_from_the_app_round_trips(svc):
    week = svc.schedule_from_json([[[60, 300]], [[60, 300]], [[60, 300]], [[60, 300]], [[60, 300]], [[0, 480]], [[0, 480]]])
    assert svc.format_schedule(week) == "Mo-Fr 01:00-05:00; Sa-So 00:00-08:00"
    assert svc.parse_schedule(svc.format_schedule(week)) == week
    for bad in ([], [[]] * 6, [[[300, 60]]] * 7, [[[0, 1441]]] * 7, [[[True, 5]]] * 7, [[[0, 10]] * 9] * 7):
        with pytest.raises(ValueError):
            svc.schedule_from_json(bad)


def test_in_window_and_bounds(svc, monkeypatch):
    monkeypatch.setattr(svc, "hours", lambda: "Mo-Fr 01:00-05:00; Sa-So 00:00-08:00")
    tz = datetime.timezone.utc
    monday_night = datetime.datetime(2026, 10, 19, 3, 30, tzinfo=tz)   # a Monday
    assert svc.in_window(monday_night)
    ends, nxt = svc.window_bounds(monday_night)
    assert ends == int(datetime.datetime(2026, 10, 19, 5, 0, tzinfo=tz).timestamp() * 1000) and nxt is None
    monday_noon = datetime.datetime(2026, 10, 19, 12, 0, tzinfo=tz)
    assert not svc.in_window(monday_noon)
    ends, nxt = svc.window_bounds(monday_noon)
    assert ends is None and nxt == int(datetime.datetime(2026, 10, 20, 1, 0, tzinfo=tz).timestamp() * 1000)
    friday_evening = datetime.datetime(2026, 10, 23, 20, 0, tzinfo=tz)
    assert svc.window_bounds(friday_evening)[1] == int(datetime.datetime(2026, 10, 24, 0, 0, tzinfo=tz).timestamp() * 1000)
    monkeypatch.setattr(svc, "hours", lambda: "never")
    assert not svc.in_window(monday_night)
    assert svc.window_bounds(monday_night) == (None, None)
