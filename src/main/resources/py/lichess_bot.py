#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Lichess bot runner with strict API compliance:
- Serializes all NON-STREAMING HTTP requests (global mutex) — "only one request at a time"
- Backs off a full minute on HTTP 429 (honors Retry-After), pushing back future calls
- Persistent outbound session; fewer lookups; randomized jitter
- Optional conservative global QPS limiter
"""

import os
import sys
import re
import time
import shlex
import platform
from pathlib import Path
import datetime
from typing import Optional, List, Dict
import threading
import asyncio
import random
import contextlib

from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import berserk
import chess
import chess.engine

# ================== USER CONFIG ==================
ENGINE_BAT = ""  # e.g. r"E:\ChessEngines\chess-engine-3.0.9.bat"
JAR_DIR = r"C:\Development\Chess-Engine\target"
JAVA_EXE = r"java"

LICHESS_TOKEN = os.environ.get("LICHESS_TOKEN") or "YOUR_TOKEN_HERE"

# Engine thinking time floor per move (seconds)
MOVE_TIME = float(os.environ.get("BOT_MOVE_TIME", "0.5"))
# Dedicated lower floor for very fast games (bullet)
BULLET_MOVE_TIME = float(os.environ.get("BOT_BULLET_MOVE_TIME", "0.4"))

# Estimated full moves to help time management
ESTIMATED_GAME_MOVES = int(os.environ.get("BOT_GAME_MOVES", "40"))

# Auto-abort if nobody has played a move after this many seconds (0 disables)
ABORT_NO_MOVE_AFTER = float(os.environ.get("BOT_ABORT_NO_MOVE_AFTER", "45"))

# Accept/decline policy
ACCEPT_VARIANTS = {"standard"}  # e.g. {"standard","chess960"}
ALLOW_RATED = True  # accept rated games?
ALLOW_CASUAL = True  # accept casual games?
ALLOW_CORRESPONDENCE = False  # accept correspondence TC?
ACCEPT_TC_TYPES = {"bullet", "blitz", "rapid", "classical"}  # which TCs to accept

# Game concurrency (helps prevent overload)
MAX_CONCURRENT_GAMES = int(os.environ.get("MAX_CONCURRENT_GAMES", "1"))

# Network hardening
HTTP_TOTAL_RETRIES = int(os.environ.get("HTTP_TOTAL_RETRIES", "5"))
HTTP_BACKOFF = float(os.environ.get("HTTP_BACKOFF", "0.5"))
HTTP_POOL_MAXSIZE = int(os.environ.get("HTTP_POOL_MAXSIZE", "20"))
HTTP_TIMEOUT = float(os.environ.get("HTTP_TIMEOUT", "10"))  # seconds
SEND_RETRIES = int(os.environ.get("SEND_RETRIES", "3"))
DISABLE_PROXIES = os.environ.get("DISABLE_PROXIES", "0") == "1"

# Make your UA descriptive (project + contact)
USER_AGENT = os.environ.get("BOT_USER_AGENT", "AlieknekChessBot/1.0 (+https://lichess.org/@/Alieknek)")

# Gentle extra delay between calls that we explicitly space in user code
API_REQUEST_DELAY = float(os.environ.get("BOT_API_REQUEST_DELAY", "2.0"))

# ---- Outbound challenge settings (idle-only) ----
ENABLE_OUTBOUND_CHALLENGES = os.environ.get("OUTBOUND_ENABLED", "1") == "1"
OUTBOUND_TC = os.environ.get("OUTBOUND_TC", "blitz")  # bullet|blitz|rapid|classical
OUTBOUND_RATED = os.environ.get("OUTBOUND_RATED", "1") == "1"
OUTBOUND_CLOCK_LIMIT = int(os.environ.get("OUTBOUND_CLOCK_LIMIT", "180"))  # seconds (3+2 default)
OUTBOUND_INCREMENT = int(os.environ.get("OUTBOUND_INCREMENT", "2"))
OUTBOUND_BLOCKLIST = {"implosio", "demolito_l1", "demolito_l2", "lunanetengine"}
RATING_DELTA = int(os.environ.get("OUTBOUND_RATING_DELTA", "100"))  # ± rating window
# Make the scanner very gentle by default
OUTBOUND_MAX_PER_CYCLE = int(os.environ.get("OUTBOUND_MAX_PER_CYCLE", "1"))  # how many to try per cycle
OUTBOUND_PERIOD_SEC = int(os.environ.get("OUTBOUND_PERIOD_SEC", "600"))  # how often to try (idle-only)
OUTBOUND_COOLDOWN_SEC = int(os.environ.get("OUTBOUND_COOLDOWN_SEC", "9000"))  # avoid re-challenging too soon
# Inspect only a small random subset of online bots for rating lookups
OUTBOUND_INSPECT_LIMIT = int(os.environ.get("OUTBOUND_INSPECT_LIMIT", "20"))


# =================================================
# ---- Auto thread planner (env vars still override) ----
def _detect_cpus():
    logical = os.cpu_count() or 2
    physical = None
    try:
        import psutil  # optional but nice to have
        physical = psutil.cpu_count(logical=False) or None
    except Exception:
        pass
    # fallback if we can't see physical cores (approximate HT)
    if physical is None:
        physical = max(1, logical // 2)
    return logical, physical


def _auto_thread_plan(max_concurrent_games: int = 1):
    logical, physical = _detect_cpus()

    # Reserve a bit for OS/Python/GC (+ extra if you allow multiple concurrent games)
    reserve = 2 + max(0, max_concurrent_games - 1)

    # Aim around physical cores, not 1.25×
    target_total = max(1, min(physical, (logical - reserve)))

    # Root split scales poorly past ~3 on most PVS/YBWC setups
    search_threads = min(3, max(1, target_total // 4))
    lazy_threads = max(1, target_total - search_threads)

    # Keep root fanout sane relative to root threads
    root_par_limit = max(24, min(96, search_threads * 12))

    return {
        "logical": logical,
        "physical": physical,
        "search": search_threads,
        "lazy": lazy_threads,
        "root_limit": root_par_limit,
    }


# If user set explicit env vars, honor them; else auto-plan.
if os.environ.get("CHESSENGINE_THREADS") or os.environ.get("CHESSENGINE_LAZY_THREADS"):
    SEARCH_THREADS = os.environ.get("CHESSENGINE_THREADS") or "4"
    LAZY_SMP_THREADS = os.environ.get("CHESSENGINE_LAZY_THREADS") or SEARCH_THREADS
    ROOT_PAR_LIMIT = os.environ.get("CHESSENGINE_ROOT_PAR_LIMIT", "128")
else:
    _plan = _auto_thread_plan(MAX_CONCURRENT_GAMES)
    SEARCH_THREADS = str(_plan["search"])
    LAZY_SMP_THREADS = str(_plan["lazy"])
    ROOT_PAR_LIMIT = str(_plan["root_limit"])
    print(
        f"[+] Auto thread plan: logical={_plan['logical']}, physical={_plan['physical']}, "
        f"search={SEARCH_THREADS}, lazy={LAZY_SMP_THREADS}, rootParallelLimit={ROOT_PAR_LIMIT}"
    )


def fail(msg: str):
    print(msg, file=sys.stderr)
    sys.exit(1)


def find_latest_uci_jar(dir_path: str) -> Optional[Path]:
    candidates = list(Path(dir_path).glob("chess-engine-*-uci.jar"))
    if not candidates:
        return None

    semver_re = re.compile(r"chess-engine-(\d+(?:\.\d+)*?)-uci\.jar$", re.IGNORECASE)

    def parse_ver(p: Path):
        m = semver_re.search(p.name)
        if not m:
            return ()
        return tuple(int(x) for x in m.group(1).split("."))

    candidates.sort(key=parse_ver)
    return candidates[-1]


def build_engine_cmd() -> List[str]:
    if ENGINE_BAT and Path(ENGINE_BAT).exists():
        return ["cmd", "/c", ENGINE_BAT]

    if not JAR_DIR or not Path(JAR_DIR).exists():
        fail(f"[-] Neither ENGINE_BAT found nor valid JAR_DIR: {JAR_DIR}")

    jar = find_latest_uci_jar(JAR_DIR)
    if not jar:
        fail(f"[-] No chess-engine-*-uci.jar found in {JAR_DIR}")

    java_opts = [
        f"-Dchessengine.searchThreads={SEARCH_THREADS}",
        f"-Dchessengine.lazySmpThreads={LAZY_SMP_THREADS}",
        f"-Dchessengine.rootParallelLimit={ROOT_PAR_LIMIT}",
        "-Dlogging.level.root=INFO",
    ]

    # Note: with -jar the main class argument is ignored; drop it.
    return [JAVA_EXE, *java_opts, "-jar", str(jar)]


def _make_retry():
    # Compatible with old/new urllib3
    methods = frozenset({"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS"})
    try:
        return Retry(
            total=HTTP_TOTAL_RETRIES,
            connect=HTTP_TOTAL_RETRIES,
            read=HTTP_TOTAL_RETRIES,
            backoff_factor=HTTP_BACKOFF,
            status_forcelist=(429, 500, 502, 503, 504),
            allowed_methods=methods,
            raise_on_status=False,
            respect_retry_after_header=True,
        )
    except TypeError:
        # Older urllib3 fallback
        return Retry(
            total=HTTP_TOTAL_RETRIES,
            connect=HTTP_TOTAL_RETRIES,
            read=HTTP_TOTAL_RETRIES,
            backoff_factor=HTTP_BACKOFF,
            status_forcelist=(429, 500, 502, 503, 504),
            method_whitelist=methods,  # deprecated in newer versions
        )


def _mount_adapter(obj, adapter: HTTPAdapter):
    if hasattr(obj, "mount"):
        obj.mount("https://", adapter)
        obj.mount("http://", adapter)
        return
    inner = getattr(obj, "session", None)
    if inner is not None and hasattr(inner, "mount"):
        inner.mount("https://", adapter)
        inner.mount("http://", adapter)


def _set_headers(obj, headers: dict):
    if hasattr(obj, "headers"):
        obj.headers.update(headers)
        return
    inner = getattr(obj, "session", None)
    if inner is not None and hasattr(inner, "headers"):
        inner.headers.update(headers)


def _set_trust_env(obj, value: bool):
    if hasattr(obj, "trust_env"):
        obj.trust_env = value
        return
    inner = getattr(obj, "session", None)
    if inner is not None and hasattr(inner, "trust_env"):
        inner.trust_env = value


# ---- Global rate limiting & strict serialization for NON-STREAM requests ----
class _GlobalRateLimiter:
    """
    Token-bucket-ish: ensures a minimum interval between request starts,
    and can be pushed forward (e.g., after Retry-After).
    """

    def __init__(self, qps: float):
        self.min_interval = 1.0 / max(qps, 0.01)
        self._lock = threading.Lock()
        self._next_ts = 0.0

    def wait(self):
        with self._lock:
            now = time.time()
            sleep_for = max(0.0, self._next_ts - now)
            base = max(now, self._next_ts)
            self._next_ts = base + self.min_interval
        if sleep_for > 0:
            time.sleep(sleep_for + random.uniform(0.0, 0.12))  # jitter

    def bump(self, seconds: float):
        with self._lock:
            self._next_ts = max(self._next_ts, time.time() + max(0.0, seconds))


# Default: 0.5 qps => one call every 2s across the whole process
BOT_QPS = float(os.environ.get("BOT_QPS", "0.5"))
_GLOBAL_RL = _GlobalRateLimiter(qps=BOT_QPS)

# This mutex guarantees **only one non-stream request in-flight at a time**.
# (Streams are allowed concurrently by design.)
_REQUEST_MUTEX = threading.Lock()


def make_hardened_session(token: str) -> berserk.TokenSession:
    """
    Create a TokenSession with robust retries, a global rate limiter,
    default per-request timeout, strict serialization of NON-STREAM calls,
    and a clear User-Agent.
    """
    ts = berserk.TokenSession(token)

    retry = _make_retry()
    adapter = HTTPAdapter(max_retries=retry, pool_maxsize=HTTP_POOL_MAXSIZE, pool_block=True)
    _mount_adapter(ts, adapter)

    _set_headers(ts, {"User-Agent": USER_AGENT, "Accept": "application/json"})
    _set_trust_env(ts, not DISABLE_PROXIES)

    # Wrap the underlying request with timeout + global pacing + 429 backoff
    _orig_request = ts.request

    def _request_wrapped(*args, **kwargs):
        # Streaming endpoints (SSE/NDJSON) stay open; do NOT serialize or set a short timeout.
        is_stream = kwargs.get("stream", False)
        if not is_stream:
            kwargs.setdefault("timeout", HTTP_TIMEOUT)
            with _REQUEST_MUTEX:  # <- only one non-stream call at a time
                _GLOBAL_RL.wait()
                try:
                    return _orig_request(*args, **kwargs)
                except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
                    status = getattr(getattr(e, "response", None), "status_code", None)
                    if status == 429:
                        # Honor Retry-After or fall back to 60s, and *pause all* future calls
                        resp = getattr(e, "response", None)
                        retry_after = None
                        if resp is not None:
                            ra = resp.headers.get("Retry-After")
                            if ra and ra.isdigit():
                                retry_after = int(ra)
                        delay = retry_after if retry_after is not None else 600
                        print(f"[warn] HTTP 429. Backing off {delay}s (per API rules).")
                        _GLOBAL_RL.bump(delay)
                        # While we hold the mutex, we sleep; this ensures no other non-stream calls happen.
                        time.sleep(delay + random.uniform(0.0, 0.5))
                        # Single retry after full backoff:
                        return _orig_request(*args, **kwargs)
                    raise
        else:
            # For long-lived event streams, just call through unthrottled.
            return _orig_request(*args, **kwargs)

    ts.request = _request_wrapped
    return ts


def connect_lichess():
    if not LICHESS_TOKEN or LICHESS_TOKEN == "YOUR_TOKEN_HERE":
        fail("[-] LICHESS_TOKEN not set. Set $env:LICHESS_TOKEN in PowerShell or hardcode it in the script.")

    session = make_hardened_session(LICHESS_TOKEN)
    client = berserk.Client(session=session)  # older berserk -> no 'timeout' kwarg
    me = client.account.get()
    username = me.get("username", "<unknown>")
    user_id = me.get("id")
    print(f"[+] Connected to Lichess as: {username}")
    return client, username, user_id


def start_engine():
    cmd = build_engine_cmd()
    print("[+] Engine command:", " ".join(shlex.quote(x) for x in cmd))
    try:
        engine = chess.engine.SimpleEngine.popen_uci(cmd)
        try:
            engine.configure({"Ponder": False, "MoveOverhead": 150})
        except chess.engine.EngineError:
            pass
    except FileNotFoundError as e:
        fail(f"[-] Could not start engine. File not found: {e}")
    except Exception as e:
        fail(f"[-] Could not start engine: {e}")

    print("[+] Engine started successfully")
    return engine


def shutdown_engine(engine: Optional[chess.engine.SimpleEngine]) -> None:
    if engine is None:
        return

    with contextlib.suppress(Exception):
        engine.stop()

    try:
        engine.quit()
    except (chess.engine.EngineTerminatedError,
            chess.engine.EngineError,
            BrokenPipeError):
        pass
    except Exception:
        kill = getattr(engine, "kill", None)
        if callable(kill):
            with contextlib.suppress(Exception):
                kill()


def is_my_turn(board: chess.Board, my_color_is_white: bool) -> bool:
    return (board.turn is chess.WHITE) == my_color_is_white


def _estimate_tc_bucket(limit_seconds: Optional[float], increment_seconds: Optional[float]) -> Optional[str]:
    """Infer a time-control bucket (bullet/blitz/rapid/classical) from clock limits."""

    if limit_seconds is None and increment_seconds is None:
        return None

    def _to_float(val, default=0.0):
        try:
            return float(val)
        except (TypeError, ValueError):
            return default

    limit = max(0.0, _to_float(limit_seconds))
    increment = max(0.0, _to_float(increment_seconds))
    total = limit + 40.0 * increment
    if total <= 0.0:
        return "bullet"

    est_minutes = total / 60.0
    if est_minutes < 3.0:
        return "bullet"
    if est_minutes <= 8.0:
        return "blitz"
    if est_minutes <= 25.0:
        return "rapid"
    return "classical"


def calc_move_time(state: dict, my_color_is_white: bool,
                   clock_initial: Optional[float] = None,
                   clock_increment: Optional[float] = None) -> float:
    key_time = "wtime" if my_color_is_white else "btime"
    key_inc = "winc" if my_color_is_white else "binc"
    time_ms = state.get(key_time)
    inc_ms = state.get(key_inc)

    if time_ms is None:
        return MOVE_TIME

    if isinstance(time_ms, datetime.timedelta):
        remaining = time_ms.total_seconds()
    else:
        try:
            remaining = float(time_ms) / 1000.0
        except (TypeError, ValueError):
            return MOVE_TIME
    remaining = max(0.0, remaining)

    if isinstance(inc_ms, datetime.timedelta):
        increment = inc_ms.total_seconds()
    else:
        try:
            increment = float(inc_ms) / 1000.0 if inc_ms else 0.0
        except (TypeError, ValueError):
            increment = 0.0
    if increment <= 0.0 and clock_increment is not None:
        try:
            inc_hint = float(clock_increment)
        except (TypeError, ValueError):
            inc_hint = 0.0
        if inc_hint > 0.0:
            increment = inc_hint

    tc_initial = clock_initial if clock_initial is not None else (remaining if remaining > 0 else None)
    tc_bucket = _estimate_tc_bucket(tc_initial, clock_increment)
    if tc_bucket is None:
        tc_bucket = "blitz"

    moves_str = state.get("moves", "")
    moves_played = len(moves_str.split()) if moves_str else 0
    estimated_moves = ESTIMATED_GAME_MOVES
    share_scale = 1.0
    inc_scale = 0.8
    margin = 0.1
    min_cap = 0.2
    cap_fraction = 0.9
    floor = MOVE_TIME

    if tc_bucket == "bullet":
        estimated_moves = max(ESTIMATED_GAME_MOVES, 48)
        share_scale = 0.38
        inc_scale = 0.5
        margin = 0.06
        min_cap = 0.1
        cap_fraction = 0.6
        bullet_floor = max(0.05, min(BULLET_MOVE_TIME, remaining * 0.45))
        floor = min(MOVE_TIME, bullet_floor)
    elif tc_bucket == "blitz":
        share_scale = 0.7
        inc_scale = 0.7
        margin = 0.08
        min_cap = 0.15
        cap_fraction = 0.75
    elif tc_bucket == "rapid":
        share_scale = 0.85
        inc_scale = 0.75
        margin = 0.1
        min_cap = 0.2
        cap_fraction = 0.85

    moves_remaining = max(8, estimated_moves - moves_played // 2)
    base_share = remaining / max(1, moves_remaining)
    think = share_scale * base_share + inc_scale * increment

    cap_threshold = max(min_cap, max(0.0, remaining - margin))
    cap_threshold = min(cap_threshold, max(0.0, remaining))
    cap_threshold = min(cap_threshold, max(0.0, remaining * cap_fraction))

    floor = min(floor, cap_threshold)
    think = min(think, cap_threshold)
    think = max(floor, think)

    return max(0.05, think)


def safe_make_move(client: berserk.Client, game_id: str, uci: str) -> bool:
    """
    Attempt to post a move. True if accepted. Handles transient errors.
    """
    delay = 0.4
    last_err = None

    for attempt in range(1, SEND_RETRIES + 1):
        try:
            client.bots.make_move(game_id, uci)
            return True

        except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
            msg = str(e)
            status = getattr(getattr(e, "response", None), "status_code", None)

            if ("Not your turn" in msg) or ("game already over" in msg) or status in (400, 409):
                print(f"[warn] Move rejected by server (not our turn / game over). uci={uci}")
                return False

            if status in (404, 410):
                print(f"[warn] Game no longer available (HTTP {status}).")
                return False

            # Be careful: strong backoff on server pressure
            transient_markers = (
                "Remote end closed connection without response",
                "Connection aborted",
                "Read timed out",
                "timed out",
                "502", "503", "504", "429",
            )
            if any(t in msg for t in transient_markers):
                if status == 429:
                    # Push the global limiter; don't block here for long (we’re in-game)
                    _GLOBAL_RL.bump(60)
                if attempt < SEND_RETRIES:
                    time.sleep(delay)
                    delay *= 1.5
                    last_err = msg
                    continue

            raise

    print(f"[warn] Failed to send move after {SEND_RETRIES} attempts: {last_err}")
    return False


def play_game(client: berserk.Client,
              engine: chess.engine.SimpleEngine,
              game_id: str,
              me_id: str,
              active_counter: "ActiveCounter") -> chess.engine.SimpleEngine:
    stream = client.bots.stream_game_state(game_id)

    my_color_is_white = None
    board = chess.Board()
    clock_initial = None
    clock_increment = None
    tc_bucket = None
    game_start_time = time.time()
    current_move_count = 0
    abort_requested = False

    abort_timer = None
    abort_lock = threading.Lock()

    def _cancel_abort_timer():
        nonlocal abort_timer
        if abort_timer is not None:
            abort_timer.cancel()
            abort_timer = None

    def _abort_inactive_game():
        nonlocal abort_requested, abort_timer
        with abort_lock:
            if abort_requested or current_move_count > 0:
                abort_timer = None
                return
            abort_requested = True
        elapsed = time.time() - game_start_time
        try:
            client.bots.abort_game(game_id)
            print(
                f"[info] Aborted inactive game {game_id} after waiting "
                f"{int(elapsed)}s for the first move"
            )
        except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
            print(f"[warn] Failed to abort inactive game {game_id}: {e}")
        except Exception as e:
            print(f"[warn] Unexpected error aborting game {game_id}: {e}")
        finally:
            abort_timer = None

    if ABORT_NO_MOVE_AFTER > 0:
        abort_timer = threading.Timer(ABORT_NO_MOVE_AFTER, _abort_inactive_game)
        abort_timer.daemon = True
        abort_timer.start()

    try:
        for event in stream:
            t = event.get("type")

            if t == "gameFull":
                white_id = event["white"]["id"]
                black_id = event["black"]["id"]
                my_color_is_white = (white_id == me_id)
                state = event.get("state", {})
                clock = event.get("clock") or {}
                if "initial" in clock:
                    clock_initial = clock.get("initial")
                if "increment" in clock:
                    clock_increment = clock.get("increment")
                tc_bucket = _estimate_tc_bucket(clock_initial, clock_increment)
                moves = state.get("moves", "")
                board = chess.Board()
                if moves:
                    for mv in moves.split():
                        board.push_uci(mv)
                current_move_count = len(moves.split()) if moves else 0
                if current_move_count > 0:
                    _cancel_abort_timer()

                if is_my_turn(board, my_color_is_white) and not board.is_game_over():
                    think_time = calc_move_time(state, my_color_is_white, clock_initial, clock_increment)
                    slack = 0.1 if tc_bucket == "bullet" else 0.15
                    try:
                        result = engine.play(board, chess.engine.Limit(time=max(0.05, think_time - slack)))
                        move_sent = safe_make_move(client, game_id, result.move.uci())
                        if not move_sent:
                            print("[warn] Move send failed, retrying once")
                            safe_make_move(client, game_id, result.move.uci())
                    except (asyncio.TimeoutError,
                            chess.engine.EngineError,
                            chess.engine.EngineTerminatedError) as e:
                        print(f"[error] engine.play failed: {repr(e)}")
                        shutdown_engine(engine)
                        try:
                            engine = start_engine()
                        except Exception as ex:
                            print(f"[error] could not restart engine: {ex}")
                        moves_list = list(board.legal_moves)
                        if moves_list:
                            fallback = random.choice(moves_list)
                            move_sent = safe_make_move(client, game_id, fallback.uci())
                            if not move_sent:
                                print("[warn] Fallback move send failed, retrying once")
                                safe_make_move(client, game_id, fallback.uci())

            elif t == "gameState":
                state = event
                moves = state.get("moves", "")
                board = chess.Board()
                if moves:
                    for mv in moves.split():
                        board.push_uci(mv)
                current_move_count = len(moves.split()) if moves else 0
                if current_move_count > 0:
                    _cancel_abort_timer()

                if board.is_game_over() or my_color_is_white is None:
                    continue

                if is_my_turn(board, my_color_is_white):
                    think_time = calc_move_time(state, my_color_is_white, clock_initial, clock_increment)
                    if tc_bucket is None and clock_initial is None:
                        key_time = "wtime" if my_color_is_white else "btime"
                        time_val = state.get(key_time)
                        if isinstance(time_val, datetime.timedelta):
                            clock_initial = time_val.total_seconds()
                        elif time_val is not None:
                            try:
                                clock_initial = float(time_val) / 1000.0
                            except (TypeError, ValueError):
                                clock_initial = None
                        if tc_bucket is None:
                            tc_bucket = _estimate_tc_bucket(clock_initial, clock_increment)
                    slack = 0.1 if tc_bucket == "bullet" else 0.15
                    try:
                        result = engine.play(board, chess.engine.Limit(time=max(0.05, think_time - slack)))
                        move_sent = safe_make_move(client, game_id, result.move.uci())
                        if not move_sent:
                            print("[warn] Move send failed, retrying once")
                            safe_make_move(client, game_id, result.move.uci())
                    except (asyncio.TimeoutError,
                            chess.engine.EngineError,
                            chess.engine.EngineTerminatedError) as e:
                        print(f"[error] engine.play failed: {repr(e)}")
                        shutdown_engine(engine)
                        try:
                            engine = start_engine()
                        except Exception as ex:
                            print(f"[error] could not restart engine: {ex}")
                        moves_list = list(board.legal_moves)
                        if moves_list:
                            fallback = random.choice(moves_list)
                            move_sent = safe_make_move(client, game_id, fallback.uci())
                            if not move_sent:
                                print("[warn] Fallback move send failed, retrying once")
                                safe_make_move(client, game_id, fallback.uci())

            elif t == "chatLine":
                print(f"[chat] {event.get('username')}: {event.get('text')}")

            elif t == "gameFinish":
                print(f"[*] Game finished: {game_id}")
                active_counter.dec()   # <- ensure counter decreases here
                break
    finally:
        _cancel_abort_timer()

    return engine


# ---------- Outbound challenge helpers ----------
PERF_FOR_TC = {
    "bullet": "bullet",
    "blitz": "blitz",
    "rapid": "rapid",
    "classical": "classical",
}


def _get_perf_rating(user_json: dict, perf_key: str):
    perfs = user_json.get("perfs", {})
    perf = perfs.get(perf_key) or {}
    return perf.get("rating"), perf.get("prov", False)


def classify_tc_from_challenge(chal: dict) -> str:
    """
    Return one of: 'bullet','blitz','rapid','classical','correspondence'
    Prefer the server's perf key; fall back to estimated minutes.
    """
    perf_key = (chal.get("perf") or {}).get("key")
    if perf_key in {"bullet", "blitz", "rapid", "classical", "correspondence"}:
        return perf_key

    tc = chal.get("timeControl") or {}
    if tc.get("type") == "correspondence":
        return "correspondence"

    bucket = _estimate_tc_bucket(tc.get("limit"), tc.get("increment"))
    return bucket or "bullet"


def find_similar_bots(client: berserk.Client,
                      my_username: str,
                      tc_type: str = "blitz",
                      delta: int = RATING_DELTA,
                      max_candidates: int = 1,
                      exclude: Optional[List[str]] = None) -> List[str]:
    exclude = set(x.lower() for x in (exclude or []))
    exclude.add(my_username.lower())
    exclude |= {u.lower() for u in OUTBOUND_BLOCKLIST}  # <- permanent exclusions
    perf_key = PERF_FOR_TC.get(tc_type, "blitz")

    # (1) My rating
    try:
        me = client.users.get_public_data(my_username)
    except Exception as e:
        print(f"[outbound] get_public_data({my_username}) failed: {e}")
        return []
    time.sleep(API_REQUEST_DELAY)
    my_rating, _ = _get_perf_rating(me, perf_key)
    if not my_rating:
        my_rating = 1200
    print(f"[outbound] my {perf_key} rating: {my_rating}")

    # (2) Online bots
    try:
        bots = list(client.bots.get_online_bots())
        source = "bots.get_online_bots"
    except AttributeError:
        try:
            bots = list(client.users.get_online_bots())
            source = "users.get_online_bots"
        except Exception as e:
            print(f"[outbound] get_online_bots failed: {e}")
            bots = []
            source = "unknown"
    time.sleep(API_REQUEST_DELAY)
    print(f"[outbound] {source} returned {len(bots)} entries")

    # (3) Filter + matches
    matches: List[tuple[str, int]] = []
    ids_needing_lookup: List[str] = []
    for b in bots:
        uid = b.get("id")
        if not uid or uid.lower() in exclude:
            continue
        perfs = b.get("perfs") or {}
        perf = perfs.get(perf_key) or {}
        r = perf.get("rating")
        prov = perf.get("prov", False)
        if r and not prov and abs(r - my_rating) <= delta:
            matches.append((uid, r))
        else:
            ids_needing_lookup.append(uid)

    print(
        f"[outbound] initial matches (from online list perfs): {len(matches)} ; need lookup for {len(ids_needing_lookup)}")

    # (4) If still short, ONE bulk lookup for a tiny random subset
    if len(matches) < max_candidates and ids_needing_lookup:
        random.shuffle(ids_needing_lookup)
        ids_sample = ids_needing_lookup[:max(1, min(OUTBOUND_INSPECT_LIMIT, 20))]
        try:
            # ONE request for up to ~20 users
            bulk = list(client.users.get_by_ids(ids_sample))
            print(f"[outbound] bulk lookup for {len(ids_sample)} users returned {len(bulk)}")
        except Exception as e:
            print(f"[outbound] bulk get_by_ids failed: {e}")
            bulk = []
        time.sleep(API_REQUEST_DELAY)

        # index by id
        idx = {u.get("id"): u for u in bulk if u.get("id")}
        for uid in ids_sample:
            u = idx.get(uid)
            if not u:
                continue
            r, prov = _get_perf_rating(u, perf_key)
            if r and not prov and abs(r - my_rating) <= delta:
                matches.append((uid, r))

    matches.sort(key=lambda t: abs(t[1] - my_rating))
    chosen = [u for (u, _) in matches[:max_candidates]]
    print(f"[outbound] chosen targets: {chosen}")
    return chosen


def challenge_user(client: berserk.Client,
                   opponent: str,
                   rated: bool,
                   clock_limit: int,
                   clock_increment: int,
                   color: str = "random") -> bool:
    try:
        client.challenges.create(
            opponent,
            rated=rated,
            variant="standard",
            clock_limit=clock_limit,
            clock_increment=clock_increment,
            color=color,
        )
        print(f"[+] Challenged {opponent} (rated={rated}, {clock_limit}+{clock_increment})")
        return True
    except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
        status = getattr(getattr(e, "response", None), "status_code", None)
        if status == 429:
            # Back off hard if challenge creation is rate-limited
            print("[warn] Challenge creation hit 429; backing off 60s.")
            _GLOBAL_RL.bump(60)
        print(f"[-] Challenge to {opponent} rejected: {e}")
        return False


class ActiveCounter:
    """Thread-safe counter of active games to keep outbound challenges in check."""

    def __init__(self):
        self._n = 0
        self._lock = threading.Lock()

    def inc(self):
        with self._lock:
            self._n += 1
            return self._n

    def dec(self):
        with self._lock:
            self._n = max(0, self._n - 1)
            return self._n

    def get(self):
        with self._lock:
            return self._n


def _retry_after_from_exc(e, default_sec=60) -> int:
    resp = getattr(e, "response", None)
    if resp is not None:
        ra = resp.headers.get("Retry-After") or resp.headers.get("retry-after")
        if ra:
            try:
                return int(ra)
            except ValueError:
                pass
    return default_sec


def outbound_challenge_loop(stop_event: threading.Event,
                            my_username: str,
                            base_token: str,
                            active_counter: ActiveCounter):
    """
    Periodically find similar-rated online bots and challenge a few.
    IDLE-ONLY. Honors Retry-After by parking the loop for that duration.
    """
    if not ENABLE_OUTBOUND_CHALLENGES:
        print("[outbound] disabled via OUTBOUND_ENABLED=0")
        return

    session = make_hardened_session(base_token)
    client = berserk.Client(session=session)

    recently_challenged: Dict[str, float] = {}
    period = max(300, OUTBOUND_PERIOD_SEC)  # gentle default
    max_per_cycle = max(1, OUTBOUND_MAX_PER_CYCLE)

    penalty_until = 0.0

    print(f"[outbound] started. period={period}s, max_per_cycle={max_per_cycle}, cooldown={OUTBOUND_COOLDOWN_SEC}s")

    while not stop_event.is_set():
        try:
            now = time.time()

            # Penalty box
            if now < penalty_until:
                sleep_for = int(penalty_until - now) + 1
                print(f"[outbound] penalty box. sleeping {sleep_for}s")
                for _ in range(sleep_for):
                    if stop_event.is_set():
                        return
                    time.sleep(1)
                continue

            # Prune cooldown map
            before = len(recently_challenged)
            recently_challenged = {u: ts for u, ts in recently_challenged.items() if now - ts < OUTBOUND_COOLDOWN_SEC}
            after = len(recently_challenged)
            if before != after:
                print(f"[outbound] pruned cooldown list: {before} -> {after}")

            # Idle-only
            if active_counter.get() != 0:
                print("[outbound] a game is active -> skipping this cycle")
                for _ in range(period):
                    if stop_event.is_set():
                        return
                    time.sleep(1)
                continue

            print("[outbound] scanning for targets...")
            targets = find_similar_bots(
                client,
                my_username=my_username,
                tc_type=OUTBOUND_TC,
                delta=RATING_DELTA,
                max_candidates=max_per_cycle,
                exclude=list(recently_challenged.keys()),
            )

            if not targets:
                print("[outbound] no targets this cycle")
            for opp in targets:
                if active_counter.get() != 0:
                    print("[outbound] game became active; stopping send loop")
                    break

                print(f"[outbound] challenging {opp} ({OUTBOUND_TC} {OUTBOUND_CLOCK_LIMIT}+{OUTBOUND_INCREMENT})")
                ok = challenge_user(
                    client,
                    opponent=opp,
                    rated=OUTBOUND_RATED,
                    clock_limit=OUTBOUND_CLOCK_LIMIT,
                    clock_increment=OUTBOUND_INCREMENT,
                    color="random",
                )
                if ok:
                    recently_challenged[opp] = time.time()

                time.sleep(max(0.5, API_REQUEST_DELAY))

        except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
            status = getattr(getattr(e, "response", None), "status_code", None)
            if status == 429:
                delay = _retry_after_from_exc(e, default_sec=600)
                print(f"[warn] outbound 429. backing off {delay}s.")
                _GLOBAL_RL.bump(delay)
                penalty_until = time.time() + delay
                for _ in range(delay):
                    if stop_event.is_set():
                        return
                    time.sleep(1)
                continue
            else:
                print(f"[warn] outbound loop API error: {e}")
        except Exception as e:
            print(f"[warn] outbound loop error: {e}")

        # Base period + jitter
        total = period + random.randint(0, 30)
        print(f"[outbound] sleeping {total}s until next cycle")
        for _ in range(total):
            if stop_event.is_set():
                break
            time.sleep(1)


def safe_accept_challenge(client: berserk.Client, chal_id: str) -> bool:
    try:
        client.bots.accept_challenge(chal_id)
        print(f"[+] Accepted challenge {chal_id}")
        return True
    except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
        status = getattr(getattr(e, "response", None), "status_code", None)
        if status == 400:
            print(f"[warn] Challenge {chal_id} vanished before accept (HTTP {status}).")
            return False
        if status in (404, 410):
            print(
                f"[info] Challenge {chal_id} no longer available before accept (HTTP {status})."
                " Likely already converted to a game."
            )
            return False
        print(f"[-] Accept failed for {chal_id}: {e}")
        return False


def safe_decline_challenge(client: berserk.Client, chal_id: str, reason: str = "variant") -> bool:
    try:
        client.bots.decline_challenge(chal_id, reason=reason)
        print(f"[+] Declined challenge {chal_id} (reason={reason})")
        return True
    except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
        status = getattr(getattr(e, "response", None), "status_code", None)
        if status in (400, 404, 410):
            print(f"[warn] Challenge {chal_id} already gone before decline (HTTP {status}).")
            return False
        print(f"[-] Decline failed for {chal_id}: {e}")
        return False


def run_bot():
    client, username, me_id = connect_lichess()
    engine = start_engine()
    print("[+] Waiting for challenges... (accepting: {})".format(", ".join(sorted(ACCEPT_VARIANTS))))

    active_counter = ActiveCounter()

    # Start outbound challenger thread (idle-only)
    stop_event = threading.Event()
    thread = threading.Thread(
        target=outbound_challenge_loop,
        args=(stop_event, username, LICHESS_TOKEN, active_counter),
        daemon=True,
    )
    thread.start()

    try:
        for event in client.bots.stream_incoming_events():
            et = event.get("type")

            if et == "challenge":
                chal = event["challenge"]
                chal_id = chal["id"]
                variant = chal["variant"]["key"]  # "standard", "chess960", ...
                tc_bucket = classify_tc_from_challenge(chal)  # bullet|blitz|rapid|classical|correspondence
                rated = chal.get("rated", False)
                challenger = chal.get("challenger", {}).get("name") or chal.get("challenger", {}).get("id", "?")
                print(f"[event] Challenge from {challenger} | variant={variant} | tc={tc_bucket} | rated={rated}")

                accept = True
                if variant not in ACCEPT_VARIANTS:
                    accept = False
                elif rated and not ALLOW_RATED:
                    accept = False
                elif (not rated) and not ALLOW_CASUAL:
                    accept = False
                elif (tc_bucket == "correspondence") and not ALLOW_CORRESPONDENCE:
                    accept = False
                elif tc_bucket not in ACCEPT_TC_TYPES:
                    accept = False
                elif active_counter.get() >= MAX_CONCURRENT_GAMES:
                    accept = False

                if accept:
                    safe_accept_challenge(client, chal_id)
                else:
                    # Use a generic reason that isn't misleading
                    reason = "later" if active_counter.get() >= MAX_CONCURRENT_GAMES else "variant"
                    safe_decline_challenge(client, chal_id, reason=reason)

            elif et == "challengeCanceled":
                chal = event.get("challenge", {})
                print(f"[event] Challenge canceled: {chal.get('id', '?')}")

            elif et == "challengeDeclined":
                chal = event.get("challenge", {})
                print(f"[event] Challenge declined (by other side?): {chal.get('id', '?')}")

            elif et == "gameStart":
                game_id = event["game"]["id"]
                print(f"[event] Game start: {game_id}")
                active_counter.inc()
                engine = play_game(client, engine, game_id, me_id, active_counter)

            elif et == "gameFinish":
                # Top-level finish notices; keep the counter accurate
                active_counter.dec()

    except KeyboardInterrupt:
        print("\n[+] Shutting down (Ctrl+C)")
    finally:
        shutdown_engine(engine)
        stop_event.set()
        try:
            thread.join(timeout=3.0)
        except Exception:
            pass


if __name__ == "__main__":
    if platform.system().lower() == "windows":
        import msvcrt  # noqa: F401
    cpu_count = os.cpu_count()
    cpu_display = cpu_count if cpu_count is not None else "unknown"
    print(f"[+] Detected logical CPU cores: {cpu_display}")
    print(f"[+] Python active threads at startup: {threading.active_count()}")
    configured_threads = str(SEARCH_THREADS).strip()
    print(
        "[+] Configured engine search threads (CHESSENGINE_THREADS/NUMBER_OF_PROCESSORS):"
        f" {configured_threads}"
    )
    run_bot()
