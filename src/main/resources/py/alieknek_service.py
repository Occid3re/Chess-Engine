#!/usr/bin/env python3
"""Play schedule, on/off switch, status file and watch page for the Alieknek Lichess bot.

Configuration (environment):
  ALIEKNEK_ENABLED      true/false (default true)
  ALIEKNEK_HOURS        play windows: "01:00-05:00" or "01:00-05:00,13:00-14:00" (every day), with
                        weekdays "Mo-Fr 01:00-05:00; Sa-So 00:00-08:00" (days not named: no play),
                        "always" or empty = always, "never" = never; a window may cross midnight
  ALIEKNEK_TZ           time zone of the windows (default Europe/Vienna)
  ALIEKNEK_DATA_DIR     where status/recent games/control file live (default ./alieknek-data)
  ALIEKNEK_STATUS_PORT  port of the watch page (0 = off)
  ALIEKNEK_CONTROL_PORT port of the control API for the play app (0 = off; keep it off the public proxy)

A control file (<data>/control.json) written by the CLI overrides the environment without a redeploy:
  python3 alieknek_service.py off | on | auto | hours 01:00-05:00 | play <minutes> | status
"""
import datetime
import hashlib
import json
import os
import re
import sys
import threading
import time
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional

try:
    from zoneinfo import ZoneInfo
except ImportError:  # Python < 3.9: windows are interpreted in local time
    ZoneInfo = None

DATA_DIR = Path(os.environ.get("ALIEKNEK_DATA_DIR", "alieknek-data"))
WATCH_DIR = Path(__file__).resolve().parent / "watch"
RECENT_LIMIT = 50  # one games-export request either way

_lock = threading.Lock()
_status = {"state": "starting", "game": None, "recent": [], "engine": {}}
# Public Lichess data (ratings, finished games with rating changes); refreshed by a background thread.
_lichess = {"ratings": {}, "games": [], "updated": None}
_engine = {"name": None}
_history = {}  # perf -> [[epochMs, rating], ...]; kept in DATA_DIR/rating-history.json
HISTORY_DAYS = 120
HISTORY_MAX_POINTS = 3000
_moves = {}  # game id -> SAN moves of recently finished games (for the replay)
_starts = {}  # game id -> start FEN of recently finished Chess960 games (standard games start normally)
_refresh = threading.Event()
# Every game of the bot: games.jsonl (recorded by the bot, with its own evaluations) and games-lichess.jsonl
# (one export of the account's earlier games). Opponent statistics and the opening experience are built from it.
ARCHIVE = "games.jsonl"
LICHESS_ARCHIVE = "games-lichess.jsonl"
EXPERIENCE_PLIES = 24  # positions from the first moves only: later ones hardly ever repeat
CORE_SINCE_MS = 1791849600000  # 2026-10-13: first game of the new core engine
_archive = {"games": {}, "opponents": {}, "experience": {}, "replies": {}, "ours": {}, "version": 0}
# Opening experience in play: a move that lost against this opponent here must be this much better to be
# played again (per net loss, capped); a line that only drew against a clearly weaker opponent a little;
# a move that went badly against everyone (>= 3 games, score < 30 %) a little as well.
PENALTY_PER_LOSS = 40
PENALTY_MAX = 120
PENALTY_DRAW_WEAKER = 15
PENALTY_GLOBAL = 20
_archive_lock = threading.Lock()
_backfill = {"lastAttempt": 0.0}
_net = {"loaded": False, "bin": None, "meta": None}
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


DAY_NAMES = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]
DAY_ALIASES = {"mo": 0, "mon": 0, "di": 1, "tue": 1, "tu": 1, "mi": 2, "wed": 2, "we": 2, "do": 3, "thu": 3, "th": 3,
               "fr": 4, "fri": 4, "sa": 5, "sat": 5, "so": 6, "sun": 6, "su": 6}
DAY_MINUTES = 24 * 60
MAX_WINDOWS_PER_DAY = 8


def _parse_time(text: str) -> int:
    hours, minutes = text.strip().split(":")
    h, m = int(hours), int(minutes)
    if not (0 <= h <= 24 and 0 <= m <= 59) or (h == 24 and m != 0):
        raise ValueError(f"time {text!r} out of range")
    return h * 60 + m


def _merge(windows) -> list:
    """Sorted, clamped to the day, overlapping or touching windows joined, empty ones dropped."""
    clean = sorted((max(0, int(a)), min(DAY_MINUTES, int(b))) for a, b in windows)
    merged = []
    for start, end in clean:
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def parse_schedule(spec: str) -> list:
    """The week as seven lists of (start, end) minutes, Monday first. Raises ValueError on nonsense.

    Groups are separated by ";". A group names days (Mo, Di, … or Mon, Tue, …, ranges like Mo-Fr, lists like
    Sa,So) and windows (HH:MM-HH:MM); a group without days means every day. A window whose end is not after
    its start crosses midnight and continues on the next day. Days no group names do not play."""
    text = (spec or "").strip()
    low = text.lower()
    if low in ("", "always", "24/7", "00:00-24:00"):
        return [[(0, DAY_MINUTES)] for _ in range(7)]
    if low in ("never", "nie", "off"):
        return [[] for _ in range(7)]
    days = [[] for _ in range(7)]
    for part in text.split(";"):
        tokens = [t for t in re.split(r"[,\s]+", part.strip()) if t]
        if not tokens:
            continue
        day_tokens = [t for t in tokens if ":" not in t]
        windows = [t for t in tokens if ":" in t]
        if not windows:
            raise ValueError(f"no window in {part.strip()!r}")
        selected = set()
        for token in day_tokens:
            if "-" in token:
                a, b = token.split("-", 1)
                ia, ib = DAY_ALIASES.get(a.lower()), DAY_ALIASES.get(b.lower())
                if ia is None or ib is None:
                    raise ValueError(f"unknown day in {token!r}")
                d = ia
                while True:
                    selected.add(d)
                    if d == ib:
                        break
                    d = (d + 1) % 7
            else:
                index = DAY_ALIASES.get(token.lower())
                if index is None:
                    raise ValueError(f"unknown day {token!r}")
                selected.add(index)
        if not day_tokens:
            selected = set(range(7))
        for window in windows:
            if "-" not in window:
                raise ValueError(f"window {window!r} must look like 01:00-05:00")
            a, b = window.split("-", 1)
            start, end = _parse_time(a), _parse_time(b)
            for d in selected:
                if end > start:
                    days[d].append((start, end))
                elif end < start:  # crosses midnight
                    days[d].append((start, DAY_MINUTES))
                    days[(d + 1) % 7].append((0, end))
    return [_merge(d) for d in days]


def _fmt_time(minutes: int) -> str:
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


def format_schedule(days) -> str:
    """The canonical text for a week: "always", "never", windows alone when every day is the same, else
    groups of consecutive equal days such as "Mo-Fr 01:00-05:00; Sa-So 00:00-08:00"."""
    days = [_merge(d) for d in days]
    if all(d == [(0, DAY_MINUTES)] for d in days):
        return "always"
    if all(not d for d in days):
        return "never"
    windows = lambda d: ",".join(f"{_fmt_time(a)}-{_fmt_time(b)}" for a, b in d)
    if all(d == days[0] for d in days):
        return windows(days[0])
    groups, i = [], 0
    while i < 7:
        j = i
        while j + 1 < 7 and days[j + 1] == days[i]:
            j += 1
        if days[i]:
            label = DAY_NAMES[i] if i == j else f"{DAY_NAMES[i]}-{DAY_NAMES[j]}"
            groups.append(f"{label} {windows(days[i])}")
        i = j + 1
    return "; ".join(groups)


def schedule_from_json(value) -> list:
    """A week sent by the app: seven lists of [start, end] minute pairs. Raises ValueError on nonsense."""
    if not isinstance(value, list) or len(value) != 7:
        raise ValueError("schedule needs seven days")
    days = []
    for day in value:
        if not isinstance(day, list) or len(day) > MAX_WINDOWS_PER_DAY:
            raise ValueError("a day is a list of at most eight windows")
        windows = []
        for window in day:
            if not isinstance(window, list) or len(window) != 2 or not all(isinstance(x, int) and not isinstance(x, bool) for x in window):
                raise ValueError("a window is [start, end] in minutes")
            start, end = window
            if not (0 <= start < end <= DAY_MINUTES):
                raise ValueError("a window runs from 0 to at most 1440 minutes, end after start")
            windows.append((start, end))
        days.append(_merge(windows))
    return days


def schedule() -> list:
    """The week in force; an unreadable spec counts as always, like before."""
    try:
        return parse_schedule(hours())
    except ValueError:
        return [[(0, DAY_MINUTES)] for _ in range(7)]


def in_window(now: Optional[datetime.datetime] = None) -> bool:
    now = now or _now()
    minute = now.hour * 60 + now.minute
    return any(start <= minute < end for start, end in schedule()[now.weekday()])


def window_bounds(now: Optional[datetime.datetime] = None):
    """(ends_at, next_at) as epoch milliseconds: when the current window ends (None outside one) and when
    the next one starts (None inside one or when the week has no window)."""
    now = now or _now()
    days = schedule()
    minute = now.hour * 60 + now.minute
    midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
    for start, end in days[now.weekday()]:
        if start <= minute < end:
            # A window that reaches midnight goes on when the next day starts at 00:00 ("Sa-So 22:00-02:00",
            # "always"): report the real end, or no end at all when the whole week is one window.
            offset, until = 0, end
            while until == DAY_MINUTES and offset < 7:
                following = days[(now.weekday() + offset + 1) % 7]
                if not following or following[0][0] != 0:
                    break
                offset, until = offset + 1, following[0][1]
            if offset >= 7:
                return None, None
            ends = midnight + datetime.timedelta(days=offset, minutes=until)
            return int(ends.timestamp() * 1000), None
    for offset in range(8):
        day = (now.weekday() + offset) % 7
        for start, end in days[day]:
            if offset == 0 and start <= minute:
                continue
            starts = midnight + datetime.timedelta(days=offset, minutes=start)
            return None, int(starts.timestamp() * 1000)
    return None, None


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
                      "title": white.get("title"), "id": white.get("id")},
            "black": {"name": black.get("name") or black.get("id") or "?", "rating": black.get("rating"),
                      "title": black.get("title"), "id": black.get("id")},
            "myColor": "white" if white.get("id") == me_id else "black",
            "rated": full_event.get("rated"),
            "speed": full_event.get("speed"),
            "variant": (full_event.get("variant") or {}).get("key") or "standard",
            "initialFen": None if full_event.get("initialFen") in (None, "startpos") else full_event.get("initialFen"),
            "clock": {"initial": clock.get("initial"), "increment": clock.get("increment")},
            "moves": [],
            "evals": [],
        }


def game_update(game_id: str, board, state: dict) -> None:
    with _lock:
        g = _status.get("game")
        if not g or g.get("id") != game_id:
            return
        g["fen"] = board.fen()
        g["lastMove"] = board.peek().uci() if board.move_stack else None
        g["ply"] = len(board.move_stack)
        g["moves"] = [m.uci() for m in board.move_stack]
        g["wtime"] = _clock_ms(state.get("wtime"))
        g["btime"] = _clock_ms(state.get("btime"))
        g["status"] = state.get("status", "started")
        g["winner"] = state.get("winner")
        g["updatedAt"] = int(time.time() * 1000)


def _uncertainty(info: dict):
    """The engine's 'info string unc 0.042' (from 4.1.2 on): how unclear the position is for its network, as the
    expected error of its evaluation in win probability (0.02 sure .. 0.10 and more very unclear); else None."""
    text = info.get("string") if info else None
    if isinstance(text, str) and text.startswith("unc "):
        try:
            return round(float(text.split()[1]), 3)
        except (IndexError, ValueError):
            return None
    return None


def engine_info(info: dict) -> None:
    score = info.get("score") if info else None
    entry = {"depth": info.get("depth") if info else None, "nps": info.get("nps") if info else None,
             "unc": _uncertainty(info)}
    if score is not None:
        pov = score.white()
        entry["mate"] = pov.mate()
        entry["cp"] = pov.score()
    with _lock:
        _status["engine"] = entry
        g = _status.get("game")
        if g is not None and info:
            # [ply searched, cp (White's view), mate, depth, uncertainty] for every move of the bot
            g.setdefault("evals", []).append([g.get("ply", 0), entry.get("cp"), entry.get("mate"), entry.get("depth"),
                                              entry.get("unc")])


def game_finished(game_id: str) -> None:
    record = None
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
                     "variant": g.get("variant") or "standard", "perf": _perf_of(g),
                     "clock": g.get("clock"), "endedAt": int(time.time() * 1000)}
            _status["recent"] = ([entry] + [r for r in _status["recent"] if r.get("id") != game_id])[:RECENT_LIMIT]
            _save("recent.json", _status["recent"])
            record = _bot_record(g, entry)
        _status["game"] = None
        _status["state"] = "idle"
    if record is not None and record["moves"]:
        _append_archive(record)
    _refresh.set()


# ------------------------------------------------------------------ game archive and opponents

def _perf_of(g: dict) -> Optional[str]:
    """Lichess rates Chess960 in one pool for all speeds: its perf is the variant, else the speed."""
    return "chess960" if g.get("variant") == "chess960" else g.get("speed")


def _bot_record(g: dict, entry: dict) -> dict:
    """The archive line of a game the bot just played (called with _lock held)."""
    my = g.get("myColor")
    me, opp = (g["white"], g["black"]) if my == "white" else (g["black"], g["white"])
    clock = g.get("clock") or {}
    seconds = (lambda v: v // 1000 if isinstance(v, int) and v >= 1000 else v)
    return {"id": g["id"], "src": "bot", "endedAt": entry["endedAt"], "speed": g.get("speed"),
            "variant": g.get("variant") or "standard", "perf": _perf_of(g), "initialFen": g.get("initialFen"),
            "rated": g.get("rated"), "clock": {"initial": seconds(clock.get("initial")),
                                               "increment": seconds(clock.get("increment"))},
            "color": my, "rating": me.get("rating"),
            "opponent": {"id": (opp.get("id") or opp.get("name") or "?").lower(), "name": opp.get("name"),
                         "title": opp.get("title"), "rating": opp.get("rating")},
            "result": entry["result"], "status": g.get("status"), "engine": _engine.get("name"),
            "moves": list(g.get("moves") or []), "evals": list(g.get("evals") or [])}


def _append_archive(record: dict) -> None:
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        with open(DATA_DIR / ARCHIVE, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, separators=(",", ":")) + "\n")
    except OSError as e:
        print(f"[archive] could not write {ARCHIVE}: {e}")
    _index_games([record])


def _read_archive(name: str) -> list:
    games = []
    try:
        with open(DATA_DIR / name, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        games.append(json.loads(line))
                    except ValueError:
                        pass  # a line cut off by a crash
    except OSError:
        pass
    return games


def load_archive() -> int:
    """Reads both archive files (the bot's own record wins over the Lichess export) and indexes them."""
    games = {g["id"]: g for g in _read_archive(LICHESS_ARCHIVE) if g.get("id")}
    games.update({g["id"]: g for g in _read_archive(ARCHIVE) if g.get("id")})
    with _archive_lock:
        _archive.update({"games": {}, "opponents": {}, "experience": {}, "replies": {}, "ours": {}})
    _index_games(sorted(games.values(), key=lambda g: g.get("endedAt") or 0))
    return len(games)


def _position_key(board) -> str:
    """Piece placement, side to move, castling and en passant: the position without the move counters."""
    return " ".join(board.fen().split()[:4])


def _index_games(records: list) -> None:
    """Adds games to the opponent statistics and the opening experience (first EXPERIENCE_PLIES plies):
    experience[position][move] = [wins, draws, losses] of the bot after playing move there,
    replies[opponent][position][move] = how often that opponent chose move there."""
    import chess  # python-chess, installed for the bot
    with _archive_lock:
        for g in records:
            if not g.get("id") or g["id"] in _archive["games"] or g.get("result") not in ("win", "draw", "loss"):
                continue
            _archive["games"][g["id"]] = {"endedAt": g.get("endedAt"), "opponent": g["opponent"]["id"],
                                          "speed": g.get("speed"), "perf": g.get("perf") or g.get("speed")}
            o = g["opponent"]
            s = _archive["opponents"].setdefault(o["id"], {
                "id": o["id"], "name": o.get("name") or o["id"], "title": o.get("title"), "rating": None,
                "games": 0, "wins": 0, "draws": 0, "losses": 0, "lastPlayed": 0,
                "core": {"games": 0, "wins": 0, "draws": 0, "losses": 0}, "repeated": 0, "sameMove": 0})
            key = {"win": "wins", "draw": "draws", "loss": "losses"}[g["result"]]
            s["games"] += 1
            s[key] += 1
            if (g.get("endedAt") or 0) >= CORE_SINCE_MS:
                s["core"]["games"] += 1
                s["core"][key] += 1
            if (g.get("endedAt") or 0) >= s["lastPlayed"]:
                s["lastPlayed"] = g.get("endedAt") or 0
                s["name"] = o.get("name") or s["name"]
                s["title"] = o.get("title") or s["title"]
                s["rating"] = o.get("rating") or s["rating"]
            outcome = {"win": 0, "draw": 1, "loss": 2}[g["result"]]
            if (g.get("variant") or "standard") != "standard":
                continue  # opening experience and replies: standard chess only (Chess960 starts elsewhere)
            board = chess.Board()
            mine = chess.WHITE if g.get("color") == "white" else chess.BLACK
            replies = _archive["replies"].setdefault(o["id"], {})
            ours = _archive["ours"].setdefault(o["id"], {})
            for uci in (g.get("moves") or [])[:EXPERIENCE_PLIES]:
                try:
                    move = chess.Move.from_uci(uci)
                except ValueError:
                    break
                if move not in board.legal_moves:
                    break
                pos = _position_key(board)
                if board.turn == mine:
                    stats = _archive["experience"].setdefault(pos, {}).setdefault(uci, [0, 0, 0])
                    stats[outcome] += 1
                    ours.setdefault(pos, {}).setdefault(uci, [0, 0, 0])[outcome] += 1
                else:
                    seen = replies.setdefault(pos, {})
                    if seen:  # the opponent was here before: did it choose the same move again?
                        s["repeated"] += 1
                        if uci in seen:
                            s["sameMove"] += 1
                    seen[uci] = seen.get(uci, 0) + 1
                board.push(move)
        _archive["version"] += 1


def recent_games_by_speed(hours: float = 24) -> dict:
    """{"bullet": n, "blitz": n, "chess960": n, ...}: the bot's finished games of the last hours per perf (Chess960
    counts as "chess960"; the outbound challenges play the least played one most often)."""
    since = int(time.time() * 1000) - int(hours * 3600 * 1000)
    counts = {}
    with _archive_lock:
        for g in _archive["games"].values():
            perf = g.get("perf") or g.get("speed")
            if (g.get("endedAt") or 0) >= since and perf:
                counts[perf] = counts.get(perf, 0) + 1
    return counts


def opponents(limit: int = 40) -> list:
    """The most recent opponents with the results against them (all games and since the core engine) and how
    predictable they are: the share of repeated opening positions in which they played the same move again."""
    with _archive_lock:
        rows = sorted(_archive["opponents"].values(), key=lambda o: -o["lastPlayed"])[:limit]
        rows = json.loads(json.dumps(rows))
    for o in rows:
        n = o["games"]
        o["score"] = (o["wins"] + o["draws"] / 2) / n if n else None
        o["determinism"] = round(o["sameMove"] / o["repeated"], 3) if o["repeated"] >= 3 else None
    return rows


def experience(board, opponent_id: Optional[str] = None) -> dict:
    """What the archive knows about this position: the bot's earlier moves with [wins, draws, losses] after
    them, and (for a known opponent) that opponent's earlier replies when it was to move here."""
    pos = _position_key(board)
    with _archive_lock:
        ours = json.loads(json.dumps(_archive["experience"].get(pos, {})))
        theirs = dict(_archive["replies"].get((opponent_id or "").lower(), {}).get(pos, {}))
    return {"moves": ours, "opponentMoves": theirs}


def root_penalties(board, opponent_id: Optional[str], my_rating: Optional[int] = None,
                   opponent_rating: Optional[int] = None) -> dict:
    """Centipawn penalties for the bot's moves in this opening position (engine option RootPenalty): the engine
    still plays a penalised move when it is better than the alternatives by more than the penalty, so it leaves
    a line it lost before only where that is cheap. Empty after the first EXPERIENCE_PLIES plies."""
    if board.ply() >= EXPERIENCE_PLIES:
        return {}
    pos = _position_key(board)
    weaker = my_rating is not None and opponent_rating is not None and my_rating - opponent_rating >= 100
    penalties = {}
    with _archive_lock:
        for uci, (w, d, l) in _archive["experience"].get(pos, {}).items():
            n = w + d + l
            if n >= 3 and (w + d / 2) / n < 0.3:
                penalties[uci] = PENALTY_GLOBAL
        for uci, (w, d, l) in _archive["ours"].get((opponent_id or "").lower(), {}).get(pos, {}).items():
            if l > w:
                penalties[uci] = max(penalties.get(uci, 0), min(PENALTY_MAX, PENALTY_PER_LOSS * (l - w)))
            elif d > 0 and w == 0 and l == 0 and weaker:
                penalties[uci] = max(penalties.get(uci, 0), PENALTY_DRAW_WEAKER)
    legal = {m.uci() for m in board.legal_moves}
    return {m: cp for m, cp in penalties.items() if m in legal}


def expected_gain(opponent_id: Optional[str], opponent_rating: Optional[int], my_rating: Optional[int]) -> float:
    """How much better than its rating predicts the bot does against this opponent: the score since the core
    engine, shrunk towards the rating expectation (4 virtual games), minus that expectation. Positive = a
    challenge is likely to gain rating; 0 for unknown opponents."""
    if not opponent_rating or not my_rating:
        return 0.0
    expected = 1.0 / (1.0 + 10 ** ((opponent_rating - my_rating) / 400.0))
    with _archive_lock:
        row = _archive["opponents"].get((opponent_id or "").lower())
        core = dict(row["core"]) if row else None
    if not core or not core["games"]:
        return 0.0
    n = core["games"]
    score = (core["wins"] + core["draws"] / 2) / n
    return (n * score + 4 * expected) / (n + 4) - expected


def backfill_archive() -> int:
    """Once (when games-lichess.jsonl is missing): the account's earlier games from ONE public export request,
    so the opponent statistics start with the whole history. Retried at most every six hours on failure."""
    target = DATA_DIR / LICHESS_ARCHIVE
    if target.exists() or time.time() - _backfill["lastAttempt"] < 6 * 3600:
        return 0
    _backfill["lastAttempt"] = time.time()
    import chess
    raw = _get(f"{LICHESS}/api/games/user/{_username()}?moves=true&opening=true",
               accept="application/x-ndjson", timeout=120).decode("utf-8")
    records = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        g = json.loads(line)
        entry = _game_entry(g)
        if not entry or entry["result"] == "aborted":
            continue
        players = g.get("players", {})
        opp = players.get("black" if entry["color"] == "white" else "white", {})
        board, ucis = chess.Board(), []
        variant = g.get("variant") or "standard"
        for san in ((g.get("moves") or "") if variant == "standard" else "").split():
            try:
                move = board.parse_san(san)
            except ValueError:
                break
            ucis.append(move.uci())
            board.push(move)
        records.append({"id": entry["id"], "src": "lichess", "endedAt": entry["endedAt"], "speed": entry["perf"],
                        "variant": variant, "perf": entry["perf"],
                        "rated": entry["rated"], "clock": entry["clock"], "color": entry["color"],
                        "rating": entry["rating"],
                        "opponent": {"id": ((opp.get("user") or {}).get("id") or entry["opponent"] or "?").lower(),
                                     "name": entry["opponent"], "title": entry["opponentTitle"],
                                     "rating": entry["opponentRating"]},
                        "result": entry["result"], "status": entry["status"], "opening": entry["opening"],
                        "moves": ucis})
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = DATA_DIR / (LICHESS_ARCHIVE + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        for r in sorted(records, key=lambda r: r.get("endedAt") or 0):
            f.write(json.dumps(r, separators=(",", ":")) + "\n")
    tmp.replace(target)
    load_archive()
    print(f"[archive] imported {len(records)} earlier games from Lichess")
    return len(records)


# ------------------------------------------------------------------ the engine's network (for the app)

def _engine_jar() -> Optional[Path]:
    folder = Path(os.environ.get("BOT_JAR_DIR", "") or ".")
    version = re.compile(r"chess-engine-(\d+(?:\.\d+)*)-uci\.jar$")
    jars = [p for p in folder.glob("chess-engine-*-uci.jar") if version.search(p.name)]
    if not jars:
        return None
    return max(jars, key=lambda p: tuple(int(x) for x in version.search(p.name).group(1).split(".")))


def network():
    """(bytes, meta) of the NNUE network inside the engine jar (or ALIEKNEK_NNUE_FILE), read once."""
    with _lock:
        if _net["loaded"]:
            return _net["bin"], _net["meta"]
    data = None
    override = os.environ.get("ALIEKNEK_NNUE_FILE")
    try:
        if override:
            data = Path(override).read_bytes()
        else:
            jar = _engine_jar()
            if jar is not None:
                with zipfile.ZipFile(jar) as z:
                    for name in ("core/nnue.bin", "BOOT-INF/classes/core/nnue.bin"):
                        if name in z.namelist():
                            data = z.read(name)
                            break
    except (OSError, zipfile.BadZipFile) as e:
        print(f"[nnue] could not read the network: {e}")
    meta = _network_meta(data) if data else None
    with _lock:
        _net.update({"loaded": True, "bin": data if meta else None, "meta": meta})
        return _net["bin"], _net["meta"]


def _network_meta(data: bytes) -> Optional[dict]:
    """Header of the network file: v1 (no output buckets), v2 (output buckets), v3 (+ king buckets of the own king's
    square: count, mirroring of kings on files e-h, the square -> bucket map; the first layer has one 768-input
    block per king bucket)."""
    if len(data) < 28 or data[:4] != b"ALNN":
        return None
    ints = [int.from_bytes(data[4 + 4 * i:8 + 4 * i], "little", signed=True) for i in range(8)]
    version, hidden, qa, qb, scale = ints[:5]
    buckets = ints[5] if version >= 2 else 1
    king_buckets, mirror, bucket_map = 1, False, [0] * 64
    if version >= 3 and len(data) >= 100:
        king_buckets, mirror, bucket_map = ints[6], bool(ints[7]), list(data[36:100])
    inputs = 768 * king_buckets
    return {"version": version, "hidden": hidden, "qa": qa, "qb": qb, "scale": scale, "buckets": buckets,
            "kingBuckets": king_buckets, "mirror": mirror, "kingBucketMap": bucket_map,
            "inputs": inputs, "params": inputs * hidden + hidden + 2 * hidden * buckets + buckets,
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


# ------------------------------------------------------------------ Lichess statistics

def _username() -> str:
    return os.environ.get("ALIEKNEK_NAME", "Alieknek")


def _get(url: str, accept: str = "application/json", timeout: int = 20) -> bytes:
    request = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "AlieknekStatus/1.0"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
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
        "variant": g.get("variant") or "standard",
        "clock": {"initial": clock.get("initial"), "increment": clock.get("increment")},
        "opening": (g.get("opening") or {}).get("name"),
        "plies": len((g.get("moves") or "").split()),
        "endedAt": g.get("lastMoveAt") or g.get("createdAt"),
    }


def engine_started(name: Optional[str]) -> None:
    """The bot reports the UCI name of the engine it runs (e.g. "Alieknek 4.0.5")."""
    with _lock:
        _engine["name"] = name


def _load_history() -> dict:
    try:
        data = json.loads((DATA_DIR / "rating-history.json").read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _record_history(ratings: dict, games: list) -> None:
    """Grows the rating curve from data we fetch anyway (no extra Lichess request; the public
    rating-history endpoint returns nothing for this bot account). A point is added when a rating
    changes; an empty history is first backfilled from the cached games (rating before + change)."""
    now = int(time.time() * 1000)
    with _lock:
        changed = False
        if not _history:
            for g in sorted(games, key=lambda g: g.get("endedAt") or 0):
                if g.get("rated") and g.get("rating") is not None and g.get("ratingDiff") is not None:
                    _history.setdefault(g["perf"], []).append([g.get("endedAt") or now, g["rating"] + g["ratingDiff"]])
                    changed = True
        for perf, data in ratings.items():
            points = _history.setdefault(perf, [])
            if not points or points[-1][1] != data["rating"]:
                points.append([now, data["rating"]])
                changed = True
            if len(points) > HISTORY_MAX_POINTS:
                del points[:len(points) - HISTORY_MAX_POINTS]
        snapshot_copy = json.loads(json.dumps(_history))
    if changed:
        _save("rating-history.json", snapshot_copy)


def _recent_history() -> dict:
    cutoff = time.time() * 1000 - HISTORY_DAYS * 86_400_000
    with _lock:
        return {perf: [pt for pt in points if pt[0] >= cutoff] for perf, points in _history.items() if points}


def refresh_lichess() -> None:
    """Fetch ratings and the last games of the bot account (public data, no token needed)."""
    name = _username()
    user = json.loads(_get(f"{LICHESS}/api/user/{name}"))
    ratings = {}
    for perf in ("bullet", "blitz", "rapid", "classical", "chess960"):
        data = (user.get("perfs") or {}).get(perf)
        if data and data.get("games"):
            ratings[perf] = {"rating": data.get("rating"), "games": data.get("games"), "rd": data.get("rd"),
                             "prov": data.get("prov", False)}
    raw = _get(f"{LICHESS}/api/games/user/{name}?max={RECENT_LIMIT}&moves=true&opening=true",
               accept="application/x-ndjson").decode("utf-8")
    games, moves, starts = [], {}, {}
    for line in raw.splitlines():
        if not line.strip():
            continue
        g = json.loads(line)
        entry = _game_entry(g)
        if entry:
            games.append(entry)
            moves[entry["id"]] = g.get("moves") or ""
            if g.get("variant") == "chess960" and g.get("initialFen"):
                starts[entry["id"]] = g["initialFen"]
    with _lock:
        _lichess.update({"ratings": ratings, "games": games, "updated": int(time.time() * 1000)})
        _moves.clear()
        _moves.update(moves)
        _starts.clear()
        _starts.update(starts)
    _record_history(ratings, games)


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


def _stats(games: list) -> dict:
    """Results of the cached games per time control and overall, plus the most played openings."""
    played = [g for g in games if g.get("result") in ("win", "draw", "loss")]

    def summary(group: list) -> dict:
        n = len(group)
        wins = sum(g["result"] == "win" for g in group)
        draws = sum(g["result"] == "draw" for g in group)
        losses = n - wins - draws
        opp = [g["opponentRating"] for g in group if g.get("opponentRating")]
        avg = round(sum(opp) / len(opp)) if opp else None
        score = (wins + draws / 2) / n if n else None
        # Linear performance estimate (FIDE-style): average opponent +-400 per net win per game.
        perf = round(avg + 400 * (wins - losses) / n) if n and avg else None
        diff = sum(g.get("ratingDiff") or 0 for g in group if g.get("rated"))
        return {"games": n, "wins": wins, "draws": draws, "losses": losses, "score": score,
                "avgOpponent": avg, "performance": perf, "ratingChange": diff}

    by_perf = {}
    for g in played:
        by_perf.setdefault(g.get("perf") or "?", []).append(g)
    openings = {}
    for g in played:
        name = (g.get("opening") or "").split(":")[0].strip()
        if name:
            openings.setdefault(name, []).append(g)
    top = sorted(openings.items(), key=lambda kv: -len(kv[1]))[:6]
    return {
        "all": summary(played),
        "white": summary([g for g in played if g.get("color") == "white"]),
        "black": summary([g for g in played if g.get("color") == "black"]),
        "byPerf": {perf: summary(group) for perf, group in by_perf.items()},
        "openings": [{"name": name, **summary(group)} for name, group in top],
    }


def _lichess_loop() -> None:
    """Two public requests per refresh. Gentle on Lichess's rate limits: every 10 minutes while the bot
    may play, every 30 minutes otherwise, plus once after each game, but never more than once a minute."""
    while True:
        started = time.time()
        try:
            if backfill_archive():
                time.sleep(60)  # one request at a time, with a pause before the next
        except Exception as e:
            print(f"[archive] import of earlier games failed: {e}")
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
        start = _starts.get(game_id)
        entry = next((g for g in _lichess["games"] if g["id"] == game_id), None)
    if sans is None:
        return None
    import chess  # python-chess, installed for the bot
    board = chess.Board(start, chess960=True) if start else chess.Board()
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


def snapshot() -> dict:
    with _lock:
        snap = json.loads(json.dumps(_status))
        lichess = json.loads(json.dumps(_lichess))
    if lichess["games"]:
        snap["recent"] = lichess["games"]
    snap["ratings"] = lichess["ratings"]
    snap["ratingsUpdated"] = lichess["updated"]
    snap["last24h"] = _last24h(lichess["games"])
    snap["stats"] = _stats(lichess["games"])
    snap["history"] = _recent_history()
    snap["opponents"] = opponents()
    with _archive_lock:
        snap["archivedGames"] = len(_archive["games"])
    with _lock:
        snap["engineName"] = _engine["name"]
    snap["outbound"] = _outbound_view()
    snap.update({
        "name": _username(),
        "enabled": enabled(),
        "hours": hours() or "always",
        "timezone": os.environ.get("ALIEKNEK_TZ", "Europe/Vienna"),
        "inWindow": in_window(),
        "schedule": [[list(w) for w in day] for day in schedule()],
        "windowEndsAt": window_bounds()[0],
        "nextWindowAt": window_bounds()[1],
        "playUntil": play_until() or None,
        "envHours": os.environ.get("ALIEKNEK_HOURS", "") or "always",
        "envEnabled": _truthy(os.environ.get("ALIEKNEK_ENABLED", "true")),
        "overrides": sorted(k for k in _control() if k in ("enabled", "hours", "playUntil")),
        "now": _now().strftime("%H:%M"),
        "serverTime": int(time.time() * 1000),
    })
    if snap["state"] not in ("playing", "no-token", "error", "starting") and not snap["enabled"]:
        snap["state"] = "disabled"
    elif snap["state"] == "idle" and not snap["inWindow"] and not snap["playUntil"]:
        snap["state"] = "sleeping"
    return snap


# ------------------------------------------------------------------ outbound challenges (for the app)

_outbound = {"activity": "starting", "since": None, "until": None, "target": None, "tc": None,
             "lastChallengeAt": None, "history": []}
_outbound_hooks = {"blocks": None, "unblock": None}
OUTBOUND_ACTIVITIES = ("starting", "off", "playing", "after-game", "searching", "waiting", "pause", "backoff")


def outbound_update(activity: str, until: Optional[float] = None, target: Optional[str] = None,
                    tc: Optional[str] = None) -> None:
    """The outbound loop's current step: searching; waiting (a challenge to target is out, until = its deadline);
    pause (until = the next look); after-game (incoming challenges first, until = the next look); playing; off
    (outside the play window or switched off); backoff (Lichess answered 429, until = end of the pause)."""
    with _lock:
        _outbound.update({"activity": activity, "since": int(time.time() * 1000),
                          "until": int(until * 1000) if until else None, "target": target, "tc": tc})


def outbound_event(kind: str, target: Optional[str] = None, tc: Optional[str] = None, detail: str = "") -> None:
    """A step for the app's list (newest first, 15 kept): challenged, accepted, declined, no-answer,
    rate-limited, refused (Lichess refused the challenge, e.g. the daily bot-game limit), no-target, unblocked."""
    with _lock:
        now_ms = int(time.time() * 1000)
        if kind == "challenged":
            _outbound["lastChallengeAt"] = now_ms
        entry = {"at": now_ms, "kind": kind, "target": target, "tc": tc, "detail": (detail or "")[:160]}
        _outbound["history"] = ([entry] + _outbound["history"])[:15]


def register_outbound_blocks(blocks, unblock) -> None:
    """The bot's block list: blocks() -> [{id, strikes, until (ms), reason}], unblock(id) lifts one."""
    _outbound_hooks.update({"blocks": blocks, "unblock": unblock})


def _outbound_view() -> dict:
    with _lock:
        view = dict(_outbound)
        view["history"] = list(_outbound["history"])
    blocks = _outbound_hooks["blocks"]
    try:
        view["blocks"] = blocks() if blocks else []
    except Exception as e:  # the status must never fail because of the list
        print(f"[outbound] block list unavailable: {e}")
        view["blocks"] = []
    return view


def unblock(bot_id: str) -> bool:
    """Lifts a bot's block (app: "Sperre aufheben"); False if there is no block list or no such entry."""
    hook = _outbound_hooks["unblock"]
    if not hook or not re.fullmatch(r"[a-z0-9_-]{1,40}", bot_id or ""):
        return False
    lifted = bool(hook(bot_id))
    if lifted:
        outbound_event("unblocked", bot_id)
    return lifted


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
        if path in ("/nnue.json", "/nnue.bin"):
            # The engine's network for the app's network view: static per engine version, revalidated by ETag.
            data, meta = network()
            if meta is None:
                return self._send(404, "text/plain", b"no network", cache=False)
            etag = '"' + meta["sha256"][:32] + '"'
            if self.headers.get("If-None-Match") == etag:
                self.send_response(304)
                self.send_header("ETag", etag)
                self.end_headers()
                return None
            if path == "/nnue.json":
                with _lock:
                    body = json.dumps({"engine": _engine["name"], **meta}).encode("utf-8")
                return self._send(200, "application/json", body, cache=True, etag=etag)
            return self._send(200, "application/octet-stream", data, cache=True, etag=etag)
        name = path.lstrip("/")
        target = (WATCH_DIR / name).resolve()
        if WATCH_DIR.resolve() not in target.parents or not target.is_file():
            return self._send(404, "text/plain", b"not found", cache=False)
        ctype = self.TYPES.get(target.suffix, "application/octet-stream")
        return self._send(200, ctype, target.read_bytes(), cache=True)

    def _send(self, code, ctype, body, cache, etag=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if etag:
            self.send_header("ETag", etag)
        # Static files are tiny; "no-cache" makes browsers pick up a new release right away.
        self.send_header("Cache-Control", "no-cache" if cache else "no-store")
        self.end_headers()
        self.wfile.write(body)


class _ControlHandler(BaseHTTPRequestHandler):
    """Internal control API: GET /control returns the status, POST /control changes the settings.

    Body fields (all optional): enabled (bool), hours ("Mo-Fr 01:00-05:00; Sa-So 00:00-08:00", "" = always,
    "never"), schedule (seven lists of [start, end] minute pairs, Monday first – stored as the hours text),
    playForMinutes (int, plays now for that long and switches the bot on; 0 ends it), reset (true = back to
    the environment), unblock (a bot id: lifts its outbound block).
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
        if "unblock" in body:  # idempotent: a block that already ran out is simply gone
            unblock(str(body["unblock"] or "").lower())
        update = {}
        if body.get("reset"):
            update.update({"enabled": None, "hours": None, "playUntil": None})
        if "enabled" in body:
            update["enabled"] = bool(body["enabled"])
        if "schedule" in body:
            try:
                update["hours"] = format_schedule(schedule_from_json(body["schedule"]))
            except ValueError as e:
                return self._send(400, {"error": str(e)})
        elif "hours" in body:
            spec = str(body["hours"] or "").strip()
            try:
                parse_schedule(spec)
            except ValueError as e:
                return self._send(400, {"error": f"hours: {e}"})
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
        _history.update(_load_history())
    threading.Thread(target=load_archive, daemon=True, name="archive").start()
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
