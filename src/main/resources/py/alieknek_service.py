#!/usr/bin/env python3
"""Play schedule, on/off switch, status file and watch page for the Alieknek Lichess bot.

Configuration (environment):
  ALIEKNEK_ENABLED      true/false (default true)
  ALIEKNEK_HOURS        play windows, e.g. "01:00-05:00" or "01:00-05:00,13:00-14:00"; empty = always
  ALIEKNEK_TZ           time zone of the windows (default Europe/Vienna)
  ALIEKNEK_DATA_DIR     where status/recent games/control file live (default ./alieknek-data)
  ALIEKNEK_STATUS_PORT  port of the watch page (0 = off)
  ALIEKNEK_CONTROL_PORT port of the control API for the play app (0 = off; keep it off the public proxy)

A control file (<data>/control.json) written by the CLI overrides the environment without a redeploy:
  python3 alieknek_service.py off | on | auto | hours 01:00-05:00 | play <minutes> | status
"""
import datetime
import json
import os
import re
import sys
import threading
import time
import urllib.request
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
# Public Lichess data (ratings, finished games with rating changes); refreshed by a background thread.
_lichess = {"ratings": {}, "games": [], "updated": None}
_moves = {}  # game id -> SAN moves of recently finished games (for the replay)
_refresh = threading.Event()
LICHESS = "https://lichess.org"
GAME_ID = re.compile(r"^[A-Za-z0-9]{8}$")


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


def play_until() -> int:
    """Epoch milliseconds until which the bot plays regardless of the windows ("play now"), 0 if unset."""
    try:
        until = int(_control().get("playUntil") or 0)
    except (TypeError, ValueError):
        return 0
    return until if until > time.time() * 1000 else 0


def accepting() -> bool:
    """True when the bot should accept challenges and challenge others."""
    return enabled() and (in_window() or play_until() > 0)


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
    _refresh.set()


# ------------------------------------------------------------------ Lichess statistics

def _username() -> str:
    return os.environ.get("ALIEKNEK_NAME", "Alieknek")


def _get(url: str, accept: str = "application/json") -> bytes:
    request = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "AlieknekStatus/1.0"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read()


def _game_entry(g: dict) -> Optional[dict]:
    me = _username().lower()
    players = g.get("players", {})
    white, black = players.get("white", {}), players.get("black", {})
    if (white.get("user") or {}).get("id") == me:
        color, mine, opp = "white", white, black
    elif (black.get("user") or {}).get("id") == me:
        color, mine, opp = "black", black, white
    else:
        return None
    winner = g.get("winner")
    status = g.get("status")
    result = "draw" if not winner else ("win" if winner == color else "loss")
    if status in ("aborted", "noStart"):
        result = "aborted"
    clock = g.get("clock") or {}
    return {
        "id": g.get("id"),
        "opponent": (opp.get("user") or {}).get("name") or opp.get("aiLevel") and f"Stockfish {opp['aiLevel']}" or "?",
        "opponentTitle": (opp.get("user") or {}).get("title"),
        "opponentRating": opp.get("rating"),
        "rating": mine.get("rating"),
        "ratingDiff": mine.get("ratingDiff"),
        "color": color,
        "result": result,
        "status": status,
        "rated": g.get("rated"),
        "perf": g.get("perf") or g.get("speed"),
        "clock": {"initial": clock.get("initial"), "increment": clock.get("increment")},
        "opening": (g.get("opening") or {}).get("name"),
        "plies": len((g.get("moves") or "").split()),
        "endedAt": g.get("lastMoveAt") or g.get("createdAt"),
    }


def refresh_lichess() -> None:
    """Fetch ratings and the last games of the bot account (public data, no token needed)."""
    name = _username()
    user = json.loads(_get(f"{LICHESS}/api/user/{name}"))
    ratings = {}
    for perf in ("bullet", "blitz", "rapid", "classical"):
        data = (user.get("perfs") or {}).get(perf)
        if data and data.get("games"):
            ratings[perf] = {"rating": data.get("rating"), "games": data.get("games"), "rd": data.get("rd"),
                             "prov": data.get("prov", False)}
    raw = _get(f"{LICHESS}/api/games/user/{name}?max={RECENT_LIMIT}&moves=true&opening=true",
               accept="application/x-ndjson").decode("utf-8")
    games, moves = [], {}
    for line in raw.splitlines():
        if not line.strip():
            continue
        g = json.loads(line)
        entry = _game_entry(g)
        if entry:
            games.append(entry)
            moves[entry["id"]] = g.get("moves") or ""
    with _lock:
        _lichess.update({"ratings": ratings, "games": games, "updated": int(time.time() * 1000)})
        _moves.clear()
        _moves.update(moves)


def _last24h(games: list) -> dict:
    since = time.time() * 1000 - 24 * 3600 * 1000
    recent = [g for g in games if (g.get("endedAt") or 0) >= since and g.get("result") != "aborted"]
    change = {}
    for g in recent:
        if g.get("rated") and g.get("ratingDiff") is not None:
            change[g["perf"]] = change.get(g["perf"], 0) + g["ratingDiff"]
    return {"games": len(recent), "wins": sum(g["result"] == "win" for g in recent),
            "draws": sum(g["result"] == "draw" for g in recent),
            "losses": sum(g["result"] == "loss" for g in recent), "ratingChange": change}


def _lichess_loop() -> None:
    """Two public requests per refresh. Gentle on Lichess's rate limits: every 10 minutes while the bot
    may play, every 30 minutes otherwise, plus once after each game, but never more than once a minute."""
    while True:
        started = time.time()
        try:
            refresh_lichess()
        except Exception as e:  # network trouble or HTTP 429: keep the last data, try again later
            print(f"[lichess-stats] refresh failed: {e}")
        _refresh.wait(600 if accepting() else 1800)
        _refresh.clear()
        time.sleep(max(5.0, 60.0 - (time.time() - started)))


def replay(game_id: str) -> Optional[dict]:
    """Positions of one of the recently finished games (from the cache only: no extra Lichess requests)."""
    if not GAME_ID.match(game_id or ""):
        return None
    with _lock:
        sans = _moves.get(game_id)
        entry = next((g for g in _lichess["games"] if g["id"] == game_id), None)
    if sans is None:
        return None
    import chess  # python-chess, installed for the bot
    board = chess.Board()
    fens, ucis = [board.fen()], []
    for san in sans.split():
        try:
            move = board.parse_san(san)
        except ValueError:
            break
        ucis.append(move.uci())
        board.push(move)
        fens.append(board.fen())
    return {"id": game_id, "game": entry, "sans": sans.split()[:len(ucis)], "ucis": ucis, "fens": fens}


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


class _ControlHandler(BaseHTTPRequestHandler):
    """Internal control API: GET /control returns the status, POST /control changes the settings.

    Body fields (all optional): enabled (bool), hours ("01:00-05:00", "" = always), playForMinutes (int,
    plays now for that long and switches the bot on; 0 ends it), reset (true = back to the environment).
    Only reachable inside the Docker network; the play API forwards requests of logged-in users.
    """

    def log_message(self, *args):
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path.startswith("/control/game/"):
            data = replay(path[len("/control/game/"):])
            return self._send(200, data) if data else self._send(404, {"error": "unknown game"})
        if path != "/control":
            return self._send(404, {"error": "not found"})
        return self._send(200, snapshot())

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/control":
            return self._send(404, {"error": "not found"})
        try:
            length = min(int(self.headers.get("Content-Length") or 0), 4096)
            body = json.loads(self.rfile.read(length) or b"{}")
            if not isinstance(body, dict):
                raise ValueError("object expected")
        except (ValueError, TypeError):
            return self._send(400, {"error": "invalid JSON"})
        update = {}
        if body.get("reset"):
            update.update({"enabled": None, "hours": None, "playUntil": None})
        if "enabled" in body:
            update["enabled"] = bool(body["enabled"])
        if "hours" in body:
            spec = str(body["hours"] or "").strip()
            if spec and spec.lower() not in ("always", "24/7") and not _parse_windows(spec):
                return self._send(400, {"error": "hours must look like 01:00-05:00"})
            update["hours"] = spec
        if "playForMinutes" in body:
            try:
                minutes = max(0, min(24 * 60, int(body["playForMinutes"])))
            except (TypeError, ValueError):
                return self._send(400, {"error": "playForMinutes must be a number"})
            update["playUntil"] = int(time.time() * 1000 + minutes * 60_000) if minutes else None
            if minutes:
                update["enabled"] = True
        _write_control(update)
        return self._send(200, snapshot())

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


def start_web(port: Optional[int] = None) -> None:
    port = int(os.environ.get("ALIEKNEK_STATUS_PORT", "0")) if port is None else port
    with _lock:
        _status["recent"] = _load_recent()
    if port <= 0:
        return
    threading.Thread(target=_lichess_loop, daemon=True, name="lichess-stats").start()
    server = ThreadingHTTPServer(("0.0.0.0", port), _Handler)
    threading.Thread(target=server.serve_forever, daemon=True, name="watch-page").start()
    print(f"[+] Watch page on port {port}")
    control_port = int(os.environ.get("ALIEKNEK_CONTROL_PORT", "0"))
    if control_port > 0:
        control = ThreadingHTTPServer(("0.0.0.0", control_port), _ControlHandler)
        threading.Thread(target=control.serve_forever, daemon=True, name="control-api").start()
        print(f"[+] Control API on port {control_port}")


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
    elif cmd == "play" and len(sys.argv) > 2:
        minutes = int(sys.argv[2])
        _write_control({"playUntil": int(time.time() * 1000 + minutes * 60_000) if minutes else None,
                        "enabled": True if minutes else _control().get("enabled")})
    elif cmd != "status":
        print(__doc__)
        sys.exit(2)
    print(f"enabled={enabled()} hours={hours() or 'always'} inWindow={in_window()} "
          f"accepting={accepting()} control={_control()}")
