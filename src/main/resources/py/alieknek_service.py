#!/usr/bin/env python3
"""Play schedule, on/off switch, status file and watch page for the Alieknek Lichess bot.

Configuration (environment):
  ALIEKNEK_ENABLED      true/false (default true)
  ALIEKNEK_HOURS        play windows, e.g. "01:00-05:00" or "01:00-05:00,13:00-14:00"; empty = always
  ALIEKNEK_TZ           time zone of the windows (default Europe/Vienna)
  ALIEKNEK_DATA_DIR     where status/recent games/control file live (default ./alieknek-data)
  ALIEKNEK_STATUS_PORT  port of the watch page (0 = off)

A control file (<data>/control.json) written by the CLI overrides the environment without a redeploy:
  python3 alieknek_service.py off | on | auto | hours 01:00-05:00 | status
"""
import datetime
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional

try:
    from zoneinfo import ZoneInfo
except ImportError:  # Python < 3.9: windows are interpreted in local time
    ZoneInfo = None

DATA_DIR = Path(os.environ.get("ALIEKNEK_DATA_DIR", "alieknek-data"))
WATCH_DIR = Path(__file__).resolve().parent / "watch"
RECENT_LIMIT = 30

_lock = threading.Lock()
_status = {"state": "starting", "game": None, "recent": [], "engine": {}}


def _truthy(value: str) -> bool:
    return str(value).strip().lower() in ("1", "true", "yes", "on")


def _control() -> dict:
    try:
        return json.loads((DATA_DIR / "control.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def enabled() -> bool:
    ctl = _control()
    if "enabled" in ctl:
        return bool(ctl["enabled"])
    return _truthy(os.environ.get("ALIEKNEK_ENABLED", "true"))


def hours() -> str:
    ctl = _control()
    return str(ctl.get("hours", os.environ.get("ALIEKNEK_HOURS", ""))).strip()


def _tz():
    name = os.environ.get("ALIEKNEK_TZ", "Europe/Vienna")
    if ZoneInfo is None:
        return None
    try:
        return ZoneInfo(name)
    except Exception:
        return None


def _now() -> datetime.datetime:
    tz = _tz()
    return datetime.datetime.now(tz) if tz else datetime.datetime.now()


def _parse_windows(spec: str):
    windows = []
    for part in spec.replace(";", ",").split(","):
        part = part.strip()
        if not part or "-" not in part:
            continue
        a, b = part.split("-", 1)
        try:
            ha, ma = (int(x) for x in a.strip().split(":"))
            hb, mb = (int(x) for x in b.strip().split(":"))
        except ValueError:
            continue
        windows.append((ha * 60 + ma, hb * 60 + mb))
    return windows


def in_window(now: Optional[datetime.datetime] = None) -> bool:
    spec = hours()
    if spec.lower() in ("", "always", "24/7", "00:00-24:00"):
        return True
    windows = _parse_windows(spec)
    if not windows:
        return True
    now = now or _now()
    minute = now.hour * 60 + now.minute
    for start, end in windows:
        if start <= end:
            if start <= minute < end:
                return True
        elif minute >= start or minute < end:  # wraps past midnight
            return True
    return False


def accepting() -> bool:
    """True when the bot should accept challenges and challenge others."""
    return enabled() and in_window()


# ------------------------------------------------------------------ status

def _load_recent():
    try:
        return json.loads((DATA_DIR / "recent.json").read_text(encoding="utf-8"))[:RECENT_LIMIT]
    except (OSError, ValueError):
        return []


def _save(name: str, payload) -> None:
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = DATA_DIR / (name + ".tmp")
        tmp.write_text(json.dumps(payload), encoding="utf-8")
        tmp.replace(DATA_DIR / name)
    except OSError:
        pass


def set_state(state: str, **extra) -> None:
    with _lock:
        _status["state"] = state
        _status.update(extra)


def _clock_ms(value) -> Optional[int]:
    if value is None:
        return None
    if isinstance(value, datetime.timedelta):
        return int(value.total_seconds() * 1000)
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def game_started(game_id: str, full_event: dict, me_id: str) -> None:
    white, black = full_event.get("white", {}) or {}, full_event.get("black", {}) or {}
    clock = full_event.get("clock") or {}
    with _lock:
        _status["state"] = "playing"
        _status["engine"] = {}
        _status["game"] = {
            "id": game_id,
            "white": {"name": white.get("name") or white.get("id") or "?", "rating": white.get("rating"),
                      "title": white.get("title")},
            "black": {"name": black.get("name") or black.get("id") or "?", "rating": black.get("rating"),
                      "title": black.get("title")},
            "myColor": "white" if white.get("id") == me_id else "black",
            "rated": full_event.get("rated"),
            "clock": {"initial": clock.get("initial"), "increment": clock.get("increment")},
        }


def game_update(game_id: str, board, state: dict) -> None:
    with _lock:
        g = _status.get("game")
        if not g or g.get("id") != game_id:
            return
        g["fen"] = board.fen()
        g["lastMove"] = board.peek().uci() if board.move_stack else None
        g["ply"] = len(board.move_stack)
        g["wtime"] = _clock_ms(state.get("wtime"))
        g["btime"] = _clock_ms(state.get("btime"))
        g["status"] = state.get("status", "started")
        g["winner"] = state.get("winner")
        g["updatedAt"] = int(time.time() * 1000)


def engine_info(info: dict) -> None:
    score = info.get("score") if info else None
    entry = {"depth": info.get("depth") if info else None, "nps": info.get("nps") if info else None}
    if score is not None:
        pov = score.white()
        entry["mate"] = pov.mate()
        entry["cp"] = pov.score()
    with _lock:
        _status["engine"] = entry


def game_finished(game_id: str) -> None:
    with _lock:
        g = _status.get("game")
        if g and g.get("id") == game_id:
            my = g.get("myColor")
            opp = g["black"] if my == "white" else g["white"]
            winner = g.get("winner")
            result = "draw" if not winner else ("win" if winner == my else "loss")
            if g.get("status") in ("aborted", "noStart"):
                result = "aborted"
            entry = {"id": game_id, "opponent": opp.get("name"), "opponentRating": opp.get("rating"),
                     "color": my, "result": result, "status": g.get("status"),
                     "clock": g.get("clock"), "endedAt": int(time.time() * 1000)}
            _status["recent"] = ([entry] + [r for r in _status["recent"] if r.get("id") != game_id])[:RECENT_LIMIT]
            _save("recent.json", _status["recent"])
        _status["game"] = None
        _status["state"] = "idle"


def snapshot() -> dict:
    with _lock:
        snap = json.loads(json.dumps(_status))
    snap.update({
        "name": os.environ.get("ALIEKNEK_NAME", "Alieknek"),
        "enabled": enabled(),
        "hours": hours() or "always",
        "timezone": os.environ.get("ALIEKNEK_TZ", "Europe/Vienna"),
        "inWindow": in_window(),
        "now": _now().strftime("%H:%M"),
        "serverTime": int(time.time() * 1000),
    })
    if snap["state"] not in ("playing", "no-token", "error", "starting") and not snap["enabled"]:
        snap["state"] = "disabled"
    elif snap["state"] == "idle" and not snap["inWindow"]:
        snap["state"] = "sleeping"
    return snap


# ------------------------------------------------------------------ watch page

class _Handler(BaseHTTPRequestHandler):
    TYPES = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
             ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml"}

    def log_message(self, *args):  # no access logs (no IP addresses kept)
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path.startswith("/alieknek"):
            path = path[len("/alieknek"):]
        if path in ("", "/"):
            path = "/index.html"
        if path == "/status.json":
            body = json.dumps(snapshot()).encode("utf-8")
            return self._send(200, "application/json", body, cache=False)
        name = path.lstrip("/")
        target = (WATCH_DIR / name).resolve()
        if WATCH_DIR.resolve() not in target.parents or not target.is_file():
            return self._send(404, "text/plain", b"not found", cache=False)
        ctype = self.TYPES.get(target.suffix, "application/octet-stream")
        return self._send(200, ctype, target.read_bytes(), cache=True)

    def _send(self, code, ctype, body, cache):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        # Static files are tiny; "no-cache" makes browsers pick up a new release right away.
        self.send_header("Cache-Control", "no-cache" if cache else "no-store")
        self.end_headers()
        self.wfile.write(body)


def start_web(port: Optional[int] = None) -> None:
    port = int(os.environ.get("ALIEKNEK_STATUS_PORT", "0")) if port is None else port
    with _lock:
        _status["recent"] = _load_recent()
    if port <= 0:
        return
    server = ThreadingHTTPServer(("0.0.0.0", port), _Handler)
    threading.Thread(target=server.serve_forever, daemon=True, name="watch-page").start()
    print(f"[+] Watch page on port {port}")


# ------------------------------------------------------------------ CLI

def _write_control(update: dict) -> dict:
    ctl = _control()
    ctl.update(update)
    for key in [k for k, v in ctl.items() if v is None]:
        del ctl[key]
    _save("control.json", ctl)
    return ctl


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "off":
        _write_control({"enabled": False})
    elif cmd == "on":
        _write_control({"enabled": True})
    elif cmd == "auto":
        _write_control({"enabled": None, "hours": None})
    elif cmd == "hours" and len(sys.argv) > 2:
        _write_control({"hours": sys.argv[2]})
    elif cmd != "status":
        print(__doc__)
        sys.exit(2)
    print(f"enabled={enabled()} hours={hours() or 'always'} inWindow={in_window()} "
          f"accepting={accepting()} control={_control()}")
