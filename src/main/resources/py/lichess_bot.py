#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Lichess bot runner with strict API compliance:
- Serializes all NON-STREAMING HTTP requests (global mutex) — "only one request at a time"
- Backs off a full minute on HTTP 429 (honors Retry-After), pushing back future calls
- Persistent outbound session; fewer lookups; randomized jitter
- Optional conservative global QPS limiter
- In-game calls (moves, abort, resign) are exempt from all of that: they go out at once, on their own
  lean adapter, because the clock is running — a two-second pause per move is a lost bullet game
- Measures its own latency per move and hands the engine that much less clock
"""

import json
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
import requests.exceptions
import asyncio
import random
import contextlib


DEFAULT_SYZYGY_NATIVE = r"C:\\Development\\Chess-Engine\\target\\classes\\natives\\win-x86_64\\Release\\JSyzygy.dll"
DEFAULT_SYZYGY_PATHS = r"C:\\Syzygy"


def _resolve_syzygy_config():
    native = os.environ.get("CHESSENGINE_SYZYGY_NATIVE")
    if not native:
        native = DEFAULT_SYZYGY_NATIVE

    paths = (
        os.environ.get("CHESSENGINE_SYZYGY_PATHS")
        or os.environ.get("CHESSENGINE_SYZYGY_PATH")
    )
    if not paths:
        paths = DEFAULT_SYZYGY_PATHS

    return native, paths

from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import berserk
import chess
import chess.engine

try:  # schedule / on-off switch / watch page (optional companion module)
    import alieknek_service as svc
except ImportError:
    svc = None

# ================== USER CONFIG ==================
ENGINE_BAT = ""  # e.g. r"E:\ChessEngines\chess-engine-3.0.9.bat"
JAR_DIR = os.environ.get("BOT_JAR_DIR", r"C:\Development\Chess-Engine\target")
JAVA_EXE = os.environ.get("BOT_JAVA", "java")

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
ALLOW_CASUAL = False  # accept casual games?
ALLOW_CORRESPONDENCE = False  # accept correspondence TC?
ACCEPT_TC_TYPES = {"bullet", "blitz", "rapid", "classical"}  # which TCs to accept

# Send the real clocks so the engine's own time manager decides (default);
# otherwise the bot computes a fixed per-move time.
USE_ENGINE_CLOCK = os.environ.get("BOT_ENGINE_CLOCK", "1") == "1"
ENGINE_MOVE_OVERHEAD_MS = int(os.environ.get("BOT_MOVE_OVERHEAD_MS", "150"))

# Rating-aware contempt: centipawns a draw is worth less per 100 rating points the opponent is below the bot
# (0 = off until a match confirms it helps), capped at BOT_CONTEMPT_MAX.
CONTEMPT_PER_100 = int(os.environ.get("BOT_CONTEMPT_PER_100", "0"))
CONTEMPT_MAX = int(os.environ.get("BOT_CONTEMPT_MAX", "30"))
# Opening experience from the game archive (engine option RootPenalty): leave lines that lost before.
USE_EXPERIENCE = os.environ.get("BOT_EXPERIENCE", "1") == "1"

# Game concurrency (helps prevent overload)
MAX_CONCURRENT_GAMES = int(os.environ.get("MAX_CONCURRENT_GAMES", "1"))
# Think on the opponent's time (only sensible when one game runs at a time)
USE_PONDER = os.environ.get("BOT_PONDER", "1" if MAX_CONCURRENT_GAMES == 1 else "0") == "1"

# Network hardening
HTTP_TOTAL_RETRIES = int(os.environ.get("HTTP_TOTAL_RETRIES", "5"))
# In-game calls: short timeout, at most one quick retry (safe_make_move retries on top of that)
IN_GAME_TIMEOUT = float(os.environ.get("BOT_MOVE_TIMEOUT", "5"))
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
OUTBOUND_BLOCKLIST = {"implosio", "demolito_l1", "demolito_l2", "lunanetengine", "hummus-bot", "honzovy-sachy"}
RATING_DELTA = int(os.environ.get("OUTBOUND_RATING_DELTA", "200"))  # ± rating window
# Time controls of outbound challenges, "perf:seconds+increment" (the least played in the last 24 h most often)
OUTBOUND_TCS_SPEC = os.environ.get("OUTBOUND_TCS") or \
    f"bullet:120+1,blitz:{OUTBOUND_CLOCK_LIMIT}+{OUTBOUND_INCREMENT},rapid:600+5"
# Pacing (Lichess rate limits): one challenge at a time, OUTBOUND_AFTER_GAME_SEC after a game (incoming challenges
# first), OUTBOUND_RETRY_SEC after a challenge nobody took (it is cancelled after OUTBOUND_WAIT_ACCEPT_SEC),
# OUTBOUND_PERIOD_SEC when no bot fits. Online bots and ratings come from a cache (OUTBOUND_CACHE_SEC).
OUTBOUND_PERIOD_SEC = int(os.environ.get("OUTBOUND_PERIOD_SEC", "120"))
# A 429 on challenging one bot: that bot is left alone this long, the loop pauses for Retry-After (else this long)
OUTBOUND_429_TARGET_SEC = int(os.environ.get("OUTBOUND_429_TARGET_SEC", "21600"))
OUTBOUND_429_PAUSE_SEC = int(os.environ.get("OUTBOUND_429_PAUSE_SEC", "300"))
OUTBOUND_AFTER_GAME_SEC = int(os.environ.get("OUTBOUND_AFTER_GAME_SEC", "20"))
OUTBOUND_RETRY_SEC = int(os.environ.get("OUTBOUND_RETRY_SEC", "90"))
OUTBOUND_WAIT_ACCEPT_SEC = int(os.environ.get("OUTBOUND_WAIT_ACCEPT_SEC", "45"))
OUTBOUND_CACHE_SEC = int(os.environ.get("OUTBOUND_CACHE_SEC", "300"))
OUTBOUND_COOLDOWN_SEC = int(os.environ.get("OUTBOUND_COOLDOWN_SEC", "9000"))  # avoid re-challenging too soon
# Inspect only a small random subset of online bots for rating lookups
OUTBOUND_INSPECT_LIMIT = int(os.environ.get("OUTBOUND_INSPECT_LIMIT", "20"))


# =================================================
# ---- Engine: Lazy SMP threads and hash (MB); the server deployment sets both through the environment ----
ENGINE_THREADS = os.environ.get("CHESSENGINE_CORE_THREADS") or os.environ.get("CHESSENGINE_THREADS") or "10"
ENGINE_HASH_MB = os.environ.get("CHESSENGINE_TT_MB", "1024")


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
    """
    Engine launcher: the latest chess-engine-*-uci.jar with a low-pause GC (ZGC by default), fixed
    Xms=Xmx and a pre-touched heap; threads, hash and Syzygy tables come from the environment.
    """
    if ENGINE_BAT and Path(ENGINE_BAT).exists():
        return ["cmd", "/c", ENGINE_BAT]

    if not JAR_DIR or not Path(JAR_DIR).exists():
        fail(f"[-] Neither ENGINE_BAT found nor valid JAR_DIR: {JAR_DIR}")

    jar = find_latest_uci_jar(JAR_DIR)
    if not jar:
        fail(f"[-] No chess-engine-*-uci.jar found in {JAR_DIR}")

    # -------- JVM defaults (override via env if you like)
    JAVA_GC  = os.environ.get("JAVA_GC", "zgc")   # "zgc" | "shenandoah" | "g1"
    JAVA_XMS = os.environ.get("JAVA_XMS", "10g")
    JAVA_XMX = os.environ.get("JAVA_XMX", "10g")

    def gc_flag(gc: str) -> str:
        g = gc.lower()
        if g == "zgc": return "-XX:+UseZGC"
        if g == "shenandoah": return "-XX:+UseShenandoahGC"
        if g == "g1": return "-XX:+UseG1GC"
        return gc  # pass-through for custom flags

    java_opts = [
        JAVA_EXE,
        f"-Xms{JAVA_XMS}",
        f"-Xmx{JAVA_XMX}",
        gc_flag(JAVA_GC),
        "--enable-native-access=ALL-UNNAMED",
        # SIMD back end of the NNUE (Vector API); without it the engine falls back to scalar code
        "--add-modules", "jdk.incubator.vector",
        "-XX:+AlwaysPreTouch",
        # harmless even if JFR off; keeps stacks deep when you enable it
        "-XX:FlightRecorderOptions=stackdepth=256",
    ]

    syzygy_native, syzygy_paths = _resolve_syzygy_config()

    engine_sysprops = [
        f"-Dchessengine.threads={ENGINE_THREADS}",
        f"-Dchessengine.tt.mb={ENGINE_HASH_MB}",
    ]

    if syzygy_native:
        engine_sysprops.append(f"-Dchessengine.syzygy.nativeLibrary={syzygy_native}")
    if syzygy_paths:
        engine_sysprops.append(f"-Dchessengine.syzygy.paths={syzygy_paths}")

    return [*java_opts, *engine_sysprops, "-jar", str(jar)]



def _make_retry():
    # Compatible with old/new urllib3
    methods = frozenset({"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS"})
    try:
        # No 429 here and no Retry-After sleep inside urllib3: the request wrapper below backs off itself,
        # with the global limiter, so a long Retry-After never blocks a thread silently.
        return Retry(
            total=HTTP_TOTAL_RETRIES,
            connect=HTTP_TOTAL_RETRIES,
            read=HTTP_TOTAL_RETRIES,
            backoff_factor=HTTP_BACKOFF,
            status_forcelist=(500, 502, 503, 504),
            allowed_methods=methods,
            raise_on_status=False,
            respect_retry_after_header=False,
        )
    except TypeError:
        # Older urllib3 fallback
        return Retry(
            total=HTTP_TOTAL_RETRIES,
            connect=HTTP_TOTAL_RETRIES,
            read=HTTP_TOTAL_RETRIES,
            backoff_factor=HTTP_BACKOFF,
            status_forcelist=(500, 502, 503, 504),
            method_whitelist=methods,  # deprecated in newer versions
        )


def _make_fast_retry():
    """For in-game calls: one quick connection retry, never a read retry (the move may already be in),
    no status retries and no Retry-After sleep – the clock is running."""
    methods = frozenset({"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS"})
    try:
        return Retry(total=1, connect=1, read=0, backoff_factor=0.2, status_forcelist=(),
                     allowed_methods=methods, raise_on_status=False, respect_retry_after_header=False)
    except TypeError:
        return Retry(total=1, connect=1, read=0, backoff_factor=0.2, status_forcelist=(), method_whitelist=methods)


# Everything under this prefix is a running game: moves, abort, resign, chat, the game stream.
IN_GAME_URL_PREFIX = "https://lichess.org/api/bot/game/"
_IN_GAME_PATH = re.compile(r"/api/bot/game/[A-Za-z0-9]+/(move|abort|resign|chat)(/|$)")


def _is_in_game_request(args, kwargs) -> bool:
    """True for a move, abort, resign or chat call of a running game (requests' Session.request(method, url, ...))."""
    url = kwargs.get("url")
    if url is None:
        url = args[1] if len(args) > 1 else (args[0] if args else "")
    return bool(_IN_GAME_PATH.search(str(url or "")))


class _LagMeter:
    """What a move costs beyond the engine's own search: handling the event, the UCI round trip and the
    POST to Lichess. The engine gets that much less clock, so it never plans with time that is already gone.
    An exponential average, floored by the per-game base and capped so one hiccup cannot starve the search."""

    def __init__(self, ceiling: float = 1.5):
        self.ceiling = ceiling
        self.ema: Optional[float] = None

    def record(self, lag_s: float) -> None:
        lag_s = max(0.0, min(float(lag_s), 5.0))
        self.ema = lag_s if self.ema is None else 0.7 * self.ema + 0.3 * lag_s

    def slack(self, base: float) -> float:
        if self.ema is None:
            return base
        return max(base, min(self.ceiling, self.ema * 1.5 + 0.05))


def _mount_adapter(obj, adapter: HTTPAdapter, prefix: Optional[str] = None):
    """Mount for all traffic, or – with a prefix – only for URLs under it (requests picks the longest match)."""
    prefixes = (prefix,) if prefix else ("https://", "http://")
    if hasattr(obj, "mount"):
        for p in prefixes:
            obj.mount(p, adapter)
        return
    inner = getattr(obj, "session", None)
    if inner is not None and hasattr(inner, "mount"):
        for p in prefixes:
            inner.mount(p, adapter)


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
    # Running games get the lean adapter: moves must not sit in a retry loop or a Retry-After sleep.
    _mount_adapter(ts, HTTPAdapter(max_retries=_make_fast_retry(), pool_maxsize=HTTP_POOL_MAXSIZE, pool_block=True),
                   prefix=IN_GAME_URL_PREFIX)

    _set_headers(ts, {"User-Agent": USER_AGENT, "Accept": "application/json"})
    _set_trust_env(ts, not DISABLE_PROXIES)

    # Wrap the underlying request with timeout + global pacing + 429 backoff
    _orig_request = ts.request

    def _request_wrapped(*args, **kwargs):
        # Streaming endpoints (SSE/NDJSON) stay open; do NOT serialize or set a short timeout.
        is_stream = kwargs.get("stream", False)
        if not is_stream and _is_in_game_request(args, kwargs):
            # A move, abort or resign: straight out, past the pacing and the mutex – the clock is running.
            kwargs.setdefault("timeout", IN_GAME_TIMEOUT)
            return _orig_request(*args, **kwargs)
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
        overhead_name = "Move Overhead" if "Move Overhead" in engine.options else "MoveOverhead"
        try:
            engine.configure({overhead_name: ENGINE_MOVE_OVERHEAD_MS})
        except chess.engine.EngineError:
            pass
    except FileNotFoundError as e:
        fail(f"[-] Could not start engine. File not found: {e}")
    except Exception as e:
        fail(f"[-] Could not start engine: {e}")

    print("[+] Engine started successfully")
    if svc is not None:
        svc.engine_started(engine.id.get("name"))
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


def _clock_seconds(value) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, datetime.timedelta):
        return value.total_seconds()
    try:
        return float(value) / 1000.0
    except (TypeError, ValueError):
        return None


def build_limit(state: dict, think_time: float, slack: float) -> chess.engine.Limit:
    """Clock-based limit (engine manages its time) or a fixed move time as fallback."""
    if USE_ENGINE_CLOCK:
        wtime = _clock_seconds(state.get("wtime"))
        btime = _clock_seconds(state.get("btime"))
        if wtime is not None and btime is not None:
            return chess.engine.Limit(
                white_clock=max(0.05, wtime - slack),
                black_clock=max(0.05, btime - slack),
                white_inc=_clock_seconds(state.get("winc")) or 0.0,
                black_inc=_clock_seconds(state.get("binc")) or 0.0,
            )
    return chess.engine.Limit(time=max(0.05, think_time - slack))


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


# ---------- Game chat: one greeting, one goodbye per game (alien style, no analysis) ----------
CHAT_ENABLED = os.environ.get("BOT_CHAT", "1") == "1"
ALIEN_GREETINGS = [
    "👽 Greetings, Earthling. Alieknek has landed - may your clock never flag.",
    "🛸 Transmission from orbit: Alieknek online. Let the pieces speak.",
    "👽 Hello from a 3 MB brain that learned chess only by playing itself. No humans were harmed in training.",
    "🛸 Fun fact from the mothership: there are more possible chess games than atoms in the observable universe.",
    "👽 Greetings! My network splits the board into 8 king zones. Where will your king live today?",
    "🛸 Alieknek here, trained on a living-room GPU and a server in Helsinki. Good luck, good game.",
    "👽 Fun fact: the longest possible chess game is 5949 moves. Let's keep it a little shorter.",
    "🛸 Earth chess - how charming. Warming up my neurons.",
    "👽 Did you know? The word 'checkmate' comes from Persian 'shah mat': the king is helpless.",
    "🛸 Signal received. Alieknek plays every move fresh - no opening book, just neurons.",
]
ALIEN_GOODBYES = {
    "win": ["👽 gg! Beaming the point back to the mothership.", "🛸 gg - thanks for the game, Earthling."],
    "draw": ["🛸 gg - the universe stays in balance.", "👽 gg - peace between the planets."],
    "loss": ["👽 gg, well played! Recalibrating my neurons...", "🛸 gg - you won this one, Earthling. Respect."],
}


def send_chat(client: berserk.Client, game_id: str, text: str) -> None:
    """Posts to the player chat in the background: a chat line must never cost clock time."""
    if not CHAT_ENABLED:
        return

    def _send():
        try:
            client.bots.post_message(game_id, text)
        except Exception as e:
            print(f"[chat] not sent: {e}")
    threading.Thread(target=_send, daemon=True).start()


def goodbye_line(result: str, positions: int) -> str:
    """The closing line: a goodbye plus how much the bot looked at (no evaluation, no analysis)."""
    line = random.choice(ALIEN_GOODBYES.get(result, ALIEN_GOODBYES["draw"]))
    if positions >= 1_000_000:
        line += f" ({positions / 1_000_000:.0f} million positions explored this game.)"
    return line


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
    abort_reason = None  # "initial" when awaiting first move, "reply" awaiting opponent reply
    abort_timer_generation = 0
    abort_lock = threading.Lock()
    abort_start_time = game_start_time
    # Our own latency per move, measured as we go: the engine gets that much less clock.
    lag = _LagMeter()
    received = time.monotonic()
    # Opponent id and both ratings (from gameFull): opening experience and contempt per move.
    who = {"opponent": None, "me": None, "them": None}
    # For the goodbye line: positions searched, the last status/winner seen, whether we greeted
    chat = {"nodes": 0, "status": None, "winner": None, "greeted": False}

    def _move_options(current_board: chess.Board) -> dict:
        options = {}
        if svc is not None and USE_EXPERIENCE and "RootPenalty" in engine.options:
            penalties = svc.root_penalties(current_board, who["opponent"], who["me"], who["them"])
            options["RootPenalty"] = " ".join(f"{m}:{cp}" for m, cp in sorted(penalties.items())) or "none"
            if penalties:
                print(f"[experience] ply {current_board.ply()} vs {who['opponent']}: penalties {penalties}")
        if CONTEMPT_PER_100 and "Contempt" in engine.options and who["me"] and who["them"]:
            options["Contempt"] = max(0, min(CONTEMPT_MAX, round((who["me"] - who["them"]) * CONTEMPT_PER_100 / 100)))
        return options

    def _think_and_send(current_board: chess.Board, current_state: dict, base_slack: float):
        """Search with the measured latency taken off the clocks, post the move, update the latency."""
        nonlocal engine
        limit = build_limit(current_state, calc_move_time(current_state, my_color_is_white, clock_initial, clock_increment),
                            lag.slack(base_slack))
        started = time.monotonic()
        result = engine.play(current_board, limit, ponder=USE_PONDER, info=chess.engine.INFO_BASIC | chess.engine.INFO_SCORE,
                             options=_move_options(current_board))
        searched = time.monotonic() - started
        if result is None or result.move is None:
            return result, False
        if svc is not None:
            svc.engine_info(result.info)
        chat["nodes"] += int((result.info or {}).get("nodes") or 0)
        sent = safe_make_move(client, game_id, result.move.uci())
        if not sent:
            print("[warn] Move send failed, retrying once")
            sent = safe_make_move(client, game_id, result.move.uci())
        if sent:
            reported = result.info.get("time") if result.info else None
            think = float(reported) if isinstance(reported, (int, float)) and reported > 0 else searched
            lag.record((time.monotonic() - received) - think)
        return result, sent

    def _cancel_abort_timer():
        nonlocal abort_timer, abort_reason
        if abort_timer is not None:
            abort_timer.cancel()
            abort_timer = None
            abort_reason = None

    def _schedule_abort_timer(reason: str):
        nonlocal abort_timer, abort_reason, abort_start_time, abort_timer_generation
        abort_timer_generation += 1
        generation = abort_timer_generation
        _cancel_abort_timer()
        abort_reason = reason
        abort_start_time = time.time()
        abort_timer = threading.Timer(
            ABORT_NO_MOVE_AFTER,
            _abort_inactive_game,
            args=(generation, reason),
        )
        abort_timer.daemon = True
        abort_timer.start()

    def _abort_inactive_game(generation: int, timer_reason: str):
        nonlocal abort_requested, abort_timer, abort_reason, abort_timer_generation
        with abort_lock:
            if generation != abort_timer_generation or abort_reason != timer_reason:
                return

            if abort_requested:
                abort_timer = None
                abort_reason = None
                return

            wait_desc = None
            if timer_reason == "initial":
                if current_move_count > 0:
                    abort_timer = None
                    abort_reason = None
                    return
                wait_desc = "for the first move"
            elif timer_reason == "reply":
                if current_move_count > 1:
                    abort_timer = None
                    abort_reason = None
                    return
                wait_desc = "for the opponent's first reply"
            else:
                abort_timer = None
                abort_reason = None
                return

            abort_requested = True

        elapsed = time.time() - abort_start_time
        try:
            client.bots.abort_game(game_id)
            print(
                f"[info] Aborted inactive game {game_id} after waiting "
                f"{int(elapsed)}s {wait_desc}"
            )
        except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
            print(f"[warn] Failed to abort inactive game {game_id}: {e}")
        except Exception as e:
            print(f"[warn] Unexpected error aborting game {game_id}: {e}")
        finally:
            abort_timer = None
            abort_reason = None

    if ABORT_NO_MOVE_AFTER > 0:
        _schedule_abort_timer("initial")

    try:
        for event in stream:
            received = time.monotonic()
            t = event.get("type")

            if t == "gameFull":
                white_id = event["white"]["id"]
                black_id = event["black"]["id"]
                my_color_is_white = (white_id == me_id)
                mine, theirs = (event["white"], event["black"]) if my_color_is_white else (event["black"], event["white"])
                who.update({"opponent": (theirs.get("id") or "").lower() or None,
                            "me": mine.get("rating"), "them": theirs.get("rating")})
                if svc is not None:
                    svc.game_started(game_id, event, me_id)
                state = event.get("state", {})
                if not chat["greeted"] and len((state.get("moves") or "").split()) < 2:
                    chat["greeted"] = True
                    send_chat(client, game_id, random.choice(ALIEN_GREETINGS))
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
                if svc is not None:
                    svc.game_update(game_id, board, state)
                if current_move_count == 0:
                    pass
                elif current_move_count == 1 and my_color_is_white and abort_reason == "reply":
                    pass
                else:
                    _cancel_abort_timer()

                if is_my_turn(board, my_color_is_white) and not board.is_game_over():
                    slack = 0.1 if tc_bucket == "bullet" else 0.15
                    try:
                        result, move_sent = _think_and_send(board, state, slack)
                        if move_sent and my_color_is_white and current_move_count == 0 and ABORT_NO_MOVE_AFTER > 0:
                            _schedule_abort_timer("reply")
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
                                move_sent = safe_make_move(client, game_id, fallback.uci())
                            if move_sent and my_color_is_white and current_move_count == 0 and ABORT_NO_MOVE_AFTER > 0:
                                _schedule_abort_timer("reply")

            elif t == "gameState":
                state = event
                chat["status"], chat["winner"] = state.get("status"), state.get("winner")
                moves = state.get("moves", "")
                board = chess.Board()
                if moves:
                    for mv in moves.split():
                        board.push_uci(mv)
                current_move_count = len(moves.split()) if moves else 0
                if svc is not None:
                    svc.game_update(game_id, board, state)
                if current_move_count == 0:
                    pass
                elif current_move_count == 1 and my_color_is_white and abort_reason == "reply":
                    pass
                else:
                    _cancel_abort_timer()

                if board.is_game_over() or my_color_is_white is None:
                    continue

                if is_my_turn(board, my_color_is_white):
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
                        result, move_sent = _think_and_send(board, state, slack)
                        if result is None or result.move is None:
                            # Engine produced no move — pick a random legal move so the
                            # game continues instead of crashing the bot.
                            print("[warn] engine.play returned no move, falling back to random legal")
                            moves_list = list(board.legal_moves)
                            if moves_list:
                                fallback = random.choice(moves_list)
                                move_sent = safe_make_move(client, game_id, fallback.uci())
                                if not move_sent:
                                    print("[warn] Fallback move send failed, retrying once")
                                    safe_make_move(client, game_id, fallback.uci())
                            else:
                                print("[warn] no legal moves available, skipping")
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
        # The goodbye, once the game is over (the stream ends after the final state, with or without gameFinish)
        if chat["greeted"] and chat["status"] not in (None, "created", "started", "aborted", "noStart") \
                and my_color_is_white is not None:
            winner = chat["winner"]
            result = "draw" if not winner else ("win" if (winner == "white") == my_color_is_white else "loss")
            send_chat(client, game_id, goodbye_line(result, chat["nodes"]))
        if svc is not None:
            svc.game_finished(game_id)

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


BLOCK_STEPS = [24 * 3600, 7 * 86400, 30 * 86400]  # 1st, 2nd, 3rd+ time a bot does not take our challenge


class OutboundBlocks:
    """Bots that declined, ignored or rate-limited (429) a challenge of ours are not asked again for a day, after
    a second time for 7 days, from the third time on for 30 days. A game against them clears the record. Kept as
    JSON in the bot's data folder, so restarts and deploys do not forget it."""

    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.Lock()
        try:
            self.data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.data = {}

    def _save(self) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data, indent=1), encoding="utf-8")
            tmp.replace(self.path)
        except OSError as e:
            print(f"[outbound] could not save the block list: {e}")

    def strike(self, bot_id: str, reason: str) -> int:
        """Counts a refusal; returns how long the bot is left alone (seconds)."""
        bot_id = bot_id.lower()
        with self.lock:
            entry = self.data.get(bot_id, {"strikes": 0})
            entry["strikes"] = entry.get("strikes", 0) + 1
            seconds = BLOCK_STEPS[min(entry["strikes"], len(BLOCK_STEPS)) - 1]
            entry.update({"until": time.time() + seconds, "reason": reason})
            self.data[bot_id] = entry
            self._save()
        print(f"[outbound] {bot_id} not asked again for {seconds // 3600} h (refusal {entry['strikes']}: {reason})")
        return seconds

    def clear(self, bot_id: str) -> None:
        """A game was played against this bot: forget its refusals."""
        with self.lock:
            if self.data.pop((bot_id or "").lower(), None) is not None:
                self._save()

    def blocked(self) -> List[str]:
        now = time.time()
        with self.lock:
            return [b for b, e in self.data.items() if e.get("until", 0) > now]

    def snapshot(self) -> List[dict]:
        """The active blocks for the app: [{id, strikes, until (ms), reason}], the soonest to expire first."""
        now = time.time()
        with self.lock:
            rows = [{"id": b, "strikes": e.get("strikes", 1), "until": int(e["until"] * 1000),
                     "reason": e.get("reason", "")} for b, e in self.data.items() if e.get("until", 0) > now]
        return sorted(rows, key=lambda r: r["until"])

    def unblock(self, bot_id: str) -> bool:
        """The app's "Sperre aufheben": forgets the bot's refusals; False if it had none."""
        with self.lock:
            found = self.data.pop((bot_id or "").lower(), None) is not None
            if found:
                self._save()
        return found


BLOCKS = OutboundBlocks((svc.DATA_DIR if svc is not None else Path(".")) / "outbound-blocks.json")
if svc is not None:
    svc.register_outbound_blocks(BLOCKS.snapshot, BLOCKS.unblock)


def _ob(activity: str, until: Optional[float] = None, target: Optional[str] = None, tc: Optional[str] = None):
    """Tells the service (and so the app) what the outbound loop is doing."""
    if svc is not None:
        svc.outbound_update(activity, until=until, target=target, tc=tc)


def _ob_event(kind: str, target: Optional[str] = None, tc: Optional[str] = None, detail: str = ""):
    if svc is not None:
        svc.outbound_event(kind, target=target, tc=tc, detail=detail)


def parse_outbound_tcs(spec: str) -> List[tuple]:
    """'bullet:120+1,blitz:180+2' -> [('bullet', 120, 1), ('blitz', 180, 2)]."""
    tcs = []
    for item in spec.split(","):
        perf, clock = item.strip().split(":")
        limit, inc = clock.split("+")
        tcs.append((perf.strip(), int(limit), int(inc)))
    return tcs


OUTBOUND_TCS = parse_outbound_tcs(OUTBOUND_TCS_SPEC)


def outbound_tc_weights(counts: dict, tcs: List[tuple] = None) -> List[float]:
    """Weight 1 / (1 + games in the last 24 h) per time control: the least played comes up most, none is starved."""
    return [1.0 / (1 + counts.get(perf, 0)) for perf, _, _ in (tcs or OUTBOUND_TCS)]


def pick_outbound_tc() -> tuple:
    counts = svc.recent_games_by_speed(24) if svc is not None else {}
    return random.choices(OUTBOUND_TCS, weights=outbound_tc_weights(counts))[0]


class LichessCache:
    """Lichess lookups for the outbound search, reused for OUTBOUND_CACHE_SEC (ratings six times as long):
    a challenge then usually costs one request instead of four."""

    def __init__(self, client: berserk.Client, my_username: str):
        self.client = client
        self.my_username = my_username
        self._me = (0.0, None)
        self._bots = (0.0, [])
        self._users: Dict[str, tuple] = {}

    def me(self) -> Optional[dict]:
        if time.time() - self._me[0] > 6 * OUTBOUND_CACHE_SEC:
            self._me = (time.time(), self.client.users.get_public_data(self.my_username))
            time.sleep(API_REQUEST_DELAY)
        return self._me[1]

    def online_bots(self) -> list:
        if time.time() - self._bots[0] > OUTBOUND_CACHE_SEC:
            self._bots = (time.time(), list(self.client.bots.get_online_bots()))
            time.sleep(API_REQUEST_DELAY)
        return self._bots[1]

    def users(self, ids: List[str]) -> Dict[str, dict]:
        now = time.time()
        missing = [u for u in ids if now - self._users.get(u, (0.0, None))[0] > 6 * OUTBOUND_CACHE_SEC]
        if missing:
            for u in self.client.users.get_by_id(*missing[:20]):  # one request for up to 20 users
                if u.get("id"):
                    self._users[u["id"]] = (now, u)
            time.sleep(API_REQUEST_DELAY)
        return {u: self._users[u][1] for u in ids if u in self._users}


def find_similar_bots(cache: LichessCache,
                      my_username: str,
                      tc_type: str = "blitz",
                      delta: int = RATING_DELTA,
                      max_candidates: int = 1,
                      exclude: Optional[List[str]] = None) -> List[str]:
    exclude = set(x.lower() for x in (exclude or []))
    exclude.add(my_username.lower())
    exclude |= {u.lower() for u in OUTBOUND_BLOCKLIST}  # <- permanent exclusions
    perf_key = PERF_FOR_TC.get(tc_type, "blitz")

    # (1) My rating in this time control (provisional counts too: a new one has to get played)
    try:
        my_rating, _ = _get_perf_rating(cache.me(), perf_key)
    except Exception as e:
        print(f"[outbound] my rating lookup failed: {e}")
        return []
    my_rating = my_rating or 1500
    print(f"[outbound] my {perf_key} rating: {my_rating}")

    # (2) Online bots (their perfs usually come with the list)
    try:
        bots = cache.online_bots()
    except Exception as e:
        print(f"[outbound] get_online_bots failed: {e}")
        return []

    # (3) Filter + matches
    matches: List[tuple[str, int]] = []
    ids_needing_lookup: List[str] = []
    for b in bots:
        uid = b.get("id")
        if not uid or uid.lower() in exclude:
            continue
        perf = (b.get("perfs") or {}).get(perf_key) or {}
        r = perf.get("rating")
        if r and not perf.get("prov", False):
            if abs(r - my_rating) <= delta:
                matches.append((uid, r))
        elif not perf:
            ids_needing_lookup.append(uid)
    print(f"[outbound] {len(bots)} bots online, {len(matches)} fit; {len(ids_needing_lookup)} without {perf_key} perf")

    # (4) If still short, ONE bulk lookup (cached) for a small random subset
    if len(matches) < max_candidates and ids_needing_lookup:
        random.shuffle(ids_needing_lookup)
        try:
            users = cache.users(ids_needing_lookup[:max(1, min(OUTBOUND_INSPECT_LIMIT, 20))])
        except Exception as e:
            print(f"[outbound] bulk get_by_id failed: {e}")
            users = {}
        for uid, u in users.items():
            r, prov = _get_perf_rating(u, perf_key)
            if r and not prov and abs(r - my_rating) <= delta:
                matches.append((uid, r))

    # Best expected rating gain first (how much better than their rating predicts the bot did against them
    # since the core engine, from the game archive), then the closest rating.
    if svc is not None:
        matches.sort(key=lambda t: (-round(svc.expected_gain(t[0], t[1], my_rating), 3), abs(t[1] - my_rating)))
    else:
        matches.sort(key=lambda t: abs(t[1] - my_rating))
    chosen = [u for (u, _) in matches[:max_candidates]]
    print(f"[outbound] chosen targets: {chosen}")
    return chosen


def challenge_user(client: berserk.Client,
                   opponent: str,
                   rated: bool,
                   clock_limit: int,
                   clock_increment: int,
                   color: str = "random") -> "tuple[bool, int, Optional[str]]":
    """Returns (sent, seconds to leave this opponent alone, challenge id). Lichess tells how long, e.g. when the
    opponent has reached its daily bot-game limit; otherwise the normal cooldown applies."""
    try:
        resp = client.challenges.create(
            opponent,
            rated=rated,
            variant="standard",
            clock_limit=clock_limit,
            clock_increment=clock_increment,
            color=color,
        )
        cid = None
        if isinstance(resp, dict):
            cid = resp.get("id") or (resp.get("challenge") or {}).get("id")
        print(f"[+] Challenged {opponent} (rated={rated}, {clock_limit}+{clock_increment})")
        return True, OUTBOUND_COOLDOWN_SEC, cid
    except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
        status = getattr(getattr(e, "response", None), "status_code", None)
        if status == 429:
            raise  # the loop parks itself for Retry-After
        print(f"[-] Challenge to {opponent} rejected: {e}")
        reason = re.search(r"'error':\s*'([^']*)'", str(e))
        _ob_event("refused", opponent, f"{clock_limit // 60 if clock_limit >= 60 else clock_limit / 60:g}+{clock_increment}",
                  reason.group(1) if reason else str(e)[:160])
        wait = re.search(r"'seconds':\s*(\d+)", str(e))
        return False, max(OUTBOUND_COOLDOWN_SEC, int(wait.group(1)) if wait else 0), None


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
    While the bot is idle (in its play window), challenges one similar-rated online bot at a time: right after a
    game (after OUTBOUND_AFTER_GAME_SEC, so incoming challenges come first), again OUTBOUND_RETRY_SEC after a
    challenge nobody took (cancelled after OUTBOUND_WAIT_ACCEPT_SEC), every OUTBOUND_PERIOD_SEC when no bot fits.
    Time controls rotate (OUTBOUND_TCS, the least played of the last 24 h most often). Honors Retry-After (429).
    """
    if not ENABLE_OUTBOUND_CHALLENGES:
        print("[outbound] disabled via OUTBOUND_ENABLED=0")
        return

    session = make_hardened_session(base_token)
    client = berserk.Client(session=session)
    cache = LichessCache(client, my_username)
    recently_challenged: Dict[str, float] = {}
    penalty_until = 0.0
    tcs = ", ".join(f"{p} {lim}+{inc}" for p, lim, inc in OUTBOUND_TCS)
    print(f"[outbound] started: {tcs}; after a game {OUTBOUND_AFTER_GAME_SEC}s, retry {OUTBOUND_RETRY_SEC}s, "
          f"no target {OUTBOUND_PERIOD_SEC}s, cooldown per bot {OUTBOUND_COOLDOWN_SEC}s")

    def pause(seconds: float, until_game: bool = False) -> None:
        """Sleeps, waking early on stop (and, with until_game, when a game starts)."""
        end = time.time() + seconds
        while time.time() < end and not stop_event.is_set():
            if until_game and active_counter.get() != 0:
                return
            time.sleep(1)

    last_target = None
    while not stop_event.is_set():
        last_target = None
        try:
            now = time.time()
            if now < penalty_until:
                _ob("backoff", until=penalty_until)
                print(f"[outbound] penalty box. sleeping {int(penalty_until - now) + 1}s")
                pause(penalty_until - now + 1)
                continue

            recently_challenged = {u: ts for u, ts in recently_challenged.items() if now - ts < OUTBOUND_COOLDOWN_SEC}

            # Only inside the configured play window / while switched on
            if svc is not None and not svc.accepting():
                _ob("off")
                pause(60)
                continue

            # A game is running: wait for its end, then give incoming challenges a moment
            if active_counter.get() != 0:
                _ob("playing")
                while active_counter.get() != 0 and not stop_event.is_set():
                    time.sleep(1)
                _ob("after-game", until=time.time() + OUTBOUND_AFTER_GAME_SEC)
                pause(OUTBOUND_AFTER_GAME_SEC, until_game=True)
                continue

            # The preferred time control first, then the others; the rating window doubles if nobody fits
            first = pick_outbound_tc()
            order = [first] + [t for t in OUTBOUND_TCS if t != first]
            _ob("searching", tc=f"{first[0]} {first[1] // 60 if first[1] >= 60 else first[1] / 60:g}+{first[2]}")
            targets, opp = [], None
            for perf, clock_limit, increment in order:
                for delta in (RATING_DELTA, 2 * RATING_DELTA):
                    print(f"[outbound] looking for a {perf} opponent ({clock_limit}+{increment}, +-{delta})")
                    targets = find_similar_bots(cache, my_username=my_username, tc_type=perf, delta=delta,
                                                max_candidates=1,
                                                exclude=list(recently_challenged.keys()) + BLOCKS.blocked())
                    if targets:
                        break
                if targets:
                    break
            if not targets:
                print(f"[outbound] no target in any time control; next look in {OUTBOUND_PERIOD_SEC}s")
                _ob_event("no-target", detail="no fitting bot online in any time control")
                _ob("pause", until=time.time() + OUTBOUND_PERIOD_SEC)
                pause(OUTBOUND_PERIOD_SEC, until_game=True)
                continue

            opp = targets[0]
            last_target = opp
            sent, leave_alone, challenge_id = challenge_user(client, opponent=opp, rated=OUTBOUND_RATED,
                                                            clock_limit=clock_limit, clock_increment=increment,
                                                            color="random")
            # Successful or not: do not ask the same bot again for a while. The map keeps entries for
            # OUTBOUND_COOLDOWN_SEC after their timestamp, so a later timestamp means a longer pause.
            recently_challenged[opp] = time.time() + leave_alone - OUTBOUND_COOLDOWN_SEC
            started = time.time()
            tc_text = f"{perf} {clock_limit // 60 if clock_limit >= 60 else clock_limit / 60:g}+{increment}"
            if sent:
                _ob_event("challenged", opp, tc_text)
                _ob("waiting", until=started + OUTBOUND_WAIT_ACCEPT_SEC, target=opp, tc=tc_text)
                pause(OUTBOUND_WAIT_ACCEPT_SEC, until_game=True)
                if active_counter.get() != 0:
                    _ob_event("accepted", opp, tc_text)
                    continue  # accepted (or another game started)
                if challenge_id:
                    try:
                        client.challenges.cancel(challenge_id)  # no second game later on
                        print(f"[outbound] {opp} did not answer; challenge cancelled")
                    except Exception as e:
                        print(f"[outbound] cancel {challenge_id} failed: {e}")
                hours = BLOCKS.strike(opp, "no answer") // 3600
                _ob_event("no-answer", opp, tc_text, f"blocked for {hours} h")
            _ob("pause", until=started + OUTBOUND_RETRY_SEC)
            pause(max(0.0, OUTBOUND_RETRY_SEC - (time.time() - started)), until_game=True)

        except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
            status = getattr(getattr(e, "response", None), "status_code", None)
            if status == 429:
                # Usually about one bot (it takes no more challenges now): leave it alone for hours and pause
                # as long as Lichess asks (Retry-After), else a few minutes - never pick the same bot right away.
                delay = _retry_after_from_exc(e, default_sec=OUTBOUND_429_PAUSE_SEC)
                if last_target:
                    recently_challenged[last_target] = time.time() + OUTBOUND_429_TARGET_SEC - OUTBOUND_COOLDOWN_SEC
                    BLOCKS.strike(last_target, "429")
                _ob_event("rate-limited", last_target, detail=f"Lichess 429, pause {delay} s")
                print(f"[warn] outbound 429 (target {last_target}): {str(e)[:160]}; pausing {delay}s")
                _GLOBAL_RL.bump(delay)
                penalty_until = time.time() + delay
            else:
                print(f"[warn] outbound loop API error: {e}")
                pause(OUTBOUND_RETRY_SEC)
        except Exception as e:
            print(f"[warn] outbound loop error: {e}")
            pause(OUTBOUND_RETRY_SEC)


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
    if svc is not None:
        svc.set_state("idle")
        print(f"[+] Play window: {svc.hours() or 'always'} | enabled={svc.enabled()}")

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
                if (chal.get("challenger") or {}).get("id") == me_id:
                    continue  # our own outgoing challenge
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
                off_hours = svc is not None and not svc.accepting()
                if off_hours:
                    accept = False

                if accept:
                    safe_accept_challenge(client, chal_id)
                else:
                    # Use a generic reason that isn't misleading
                    reason = "later" if off_hours or active_counter.get() >= MAX_CONCURRENT_GAMES else "variant"
                    safe_decline_challenge(client, chal_id, reason=reason)

            elif et == "challengeCanceled":
                chal = event.get("challenge", {})
                print(f"[event] Challenge canceled: {chal.get('id', '?')}")

            elif et == "challengeDeclined":
                chal = event.get("challenge", {})
                print(f"[event] Challenge declined (by other side?): {chal.get('id', '?')}")
                challenger = ((chal.get("challenger") or {}).get("id") or "").lower()
                dest = ((chal.get("destUser") or {}).get("id") or "").lower()
                if challenger == me_id.lower() and dest:
                    reason = str(chal.get("declineReasonKey") or chal.get("declineReason") or "?")
                    hours = BLOCKS.strike(dest, "declined: " + reason) // 3600
                    _ob_event("declined", dest, detail=f"{reason}; blocked for {hours} h")

            elif et == "gameStart":
                game_id = event["game"]["id"]
                print(f"[event] Game start: {game_id}")
                BLOCKS.clear(((event["game"].get("opponent") or {}).get("id") or ""))
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
    print(f"[+] Engine threads (CHESSENGINE_THREADS): {ENGINE_THREADS}, hash: {ENGINE_HASH_MB} MB")
    if svc is not None:
        svc.start_web()
        if LICHESS_TOKEN in ("", "YOUR_TOKEN_HERE"):
            print("[!] No LICHESS_TOKEN configured; serving the status page only.")
            svc.set_state("no-token")
            while True:
                time.sleep(3600)
        while not svc.enabled():
            svc.set_state("disabled")
            time.sleep(30)
    # Resilient run loop: auto-reconnect on stream disconnect or transient errors.
    MAX_RETRIES = 50
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            run_bot()
            break  # clean exit
        except (requests.exceptions.ChunkedEncodingError,
                requests.exceptions.ConnectionError,
                requests.exceptions.Timeout) as e:
            wait = min(30, 5 * attempt)
            print(f"[!] Stream disconnected (attempt {attempt}/{MAX_RETRIES}): {type(e).__name__}: {e}")
            print(f"[!] Reconnecting in {wait}s...")
            time.sleep(wait)
        except KeyboardInterrupt:
            print("[!] KeyboardInterrupt — shutting down.")
            break
        except Exception as e:
            wait = min(60, 10 * attempt)
            print(f"[!] Unexpected error (attempt {attempt}/{MAX_RETRIES}): {type(e).__name__}: {e}")
            print(f"[!] Reconnecting in {wait}s...")
            time.sleep(wait)
    else:
        print(f"[!] Max retries ({MAX_RETRIES}) exhausted. Exiting.")
