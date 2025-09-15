#!/usr/bin/env python3
import os
import sys
import re
import time
import shlex
import platform
from pathlib import Path
import datetime
from typing import Optional, List
import threading
import asyncio
import random

from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import berserk
import chess
import chess.engine

# ================== USER CONFIG ==================
ENGINE_BAT = ""  # e.g. r"E:\ChessEngines\chess-engine-3.0.9.bat"
JAR_DIR = r"C:\Development\Chess-Engine\target"
JAVA_EXE = r"java"

# Java opts mirrored from your .bat
ROOT_PAR_LIMIT = os.environ.get("CHESSENGINE_ROOT_PAR_LIMIT", "128")
SEARCH_THREADS = (
        os.environ.get("CHESSENGINE_THREADS")
        or os.environ.get("NUMBER_OF_PROCESSORS")
        or "4"
)

LICHESS_TOKEN = os.environ.get("LICHESS_TOKEN") or "YOUR_TOKEN_HERE"

# Engine thinking time floor per move (seconds)
MOVE_TIME = float(os.environ.get("BOT_MOVE_TIME", "0.5"))

# Estimated number of full moves in a typical game.  Used for time management
# to distribute the remaining time across upcoming moves more intelligently.
ESTIMATED_GAME_MOVES = int(os.environ.get("BOT_GAME_MOVES", "40"))

# Accept/decline policy
ACCEPT_VARIANTS = {"standard"}                           # e.g. {"standard","chess960"}
ALLOW_RATED = True                                       # accept rated games?
ALLOW_CASUAL = True                                      # accept casual games?
ALLOW_CORRESPONDENCE = False                             # accept correspondence TC?
ACCEPT_TC_TYPES = {"bullet", "blitz", "rapid", "classical"}  # which TCs to accept

# Game concurrency (helps prevent overload)
MAX_CONCURRENT_GAMES = int(os.environ.get("MAX_CONCURRENT_GAMES", "2"))

# Network hardening
HTTP_TOTAL_RETRIES = int(os.environ.get("HTTP_TOTAL_RETRIES", "5"))
HTTP_BACKOFF = float(os.environ.get("HTTP_BACKOFF", "0.5"))
HTTP_POOL_MAXSIZE = int(os.environ.get("HTTP_POOL_MAXSIZE", "20"))
HTTP_TIMEOUT = float(os.environ.get("HTTP_TIMEOUT", "10"))  # seconds
SEND_RETRIES = int(os.environ.get("SEND_RETRIES", "3"))
DISABLE_PROXIES = os.environ.get("DISABLE_PROXIES", "0") == "1"

USER_AGENT = os.environ.get("BOT_USER_AGENT", "JuliusChessBot/0.1")

# ---- Outbound challenge settings (idle-only) ----
ENABLE_OUTBOUND_CHALLENGES = os.environ.get("OUTBOUND_ENABLED", "1") == "1"
OUTBOUND_TC = os.environ.get("OUTBOUND_TC", "blitz")        # bullet|blitz|rapid|classical
OUTBOUND_RATED = os.environ.get("OUTBOUND_RATED", "1") == "1"
OUTBOUND_CLOCK_LIMIT = int(os.environ.get("OUTBOUND_CLOCK_LIMIT", "180"))      # seconds (3+2 default)
OUTBOUND_INCREMENT = int(os.environ.get("OUTBOUND_INCREMENT", "2"))
RATING_DELTA = int(os.environ.get("OUTBOUND_RATING_DELTA", "100"))             # ± rating window
OUTBOUND_MAX_PER_CYCLE = int(os.environ.get("OUTBOUND_MAX_PER_CYCLE", "3"))    # how many to try per cycle
OUTBOUND_PERIOD_SEC = int(os.environ.get("OUTBOUND_PERIOD_SEC", "120"))        # how often to try
# =================================================


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
        f"-Dchessengine.rootParallelLimit={ROOT_PAR_LIMIT}",
        "-Dlogging.level.root=ERROR",
    ]

    return [
        JAVA_EXE,
        *java_opts,
        "-jar",
        str(jar),
        "julius.game.chessengine.uci.UciMain",
    ]


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


def make_hardened_session(token: str) -> berserk.TokenSession:
    """
    Create a TokenSession with robust retries for GET/POST, a larger pool,
    default per-request timeout, and a clear User-Agent.
    Works with both TokenSession-as-Session and TokenSession-wrapping-Session.
    """
    ts = berserk.TokenSession(token)  # shape varies by berserk version

    retry = _make_retry()
    adapter = HTTPAdapter(max_retries=retry, pool_maxsize=HTTP_POOL_MAXSIZE, pool_block=True)
    _mount_adapter(ts, adapter)

    _set_headers(ts, {"User-Agent": USER_AGENT, "Accept": "application/json"})
    _set_trust_env(ts, not DISABLE_PROXIES)

    # Inject a default timeout into every API call
    _orig_request = ts.request

    def _request_with_timeout(*args, **kwargs):
        kwargs.setdefault("timeout", HTTP_TIMEOUT)
        return _orig_request(*args, **kwargs)

    ts.request = _request_with_timeout
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
    except FileNotFoundError as e:
        fail(f"[-] Could not start engine. File not found: {e}")
    except Exception as e:
        fail(f"[-] Could not start engine: {e}")

    print("[+] Engine started successfully")
    return engine


def is_my_turn(board: chess.Board, my_color_is_white: bool) -> bool:
    return (board.turn is chess.WHITE) == my_color_is_white


def calc_move_time(state: dict, my_color_is_white: bool) -> float:
    key_time = "wtime" if my_color_is_white else "btime"
    key_inc = "winc" if my_color_is_white else "binc"
    time_ms = state.get(key_time)
    inc_ms = state.get(key_inc, 0)

    if time_ms is None:
        return MOVE_TIME

    if isinstance(time_ms, datetime.timedelta):
        remaining = time_ms.total_seconds()
    else:
        remaining = float(time_ms) / 1000.0

    if isinstance(inc_ms, datetime.timedelta):
        increment = inc_ms.total_seconds()
    else:
        increment = float(inc_ms) / 1000.0 if inc_ms else 0.0

    # Estimate how many full moves are left in the game using the moves list
    moves_str = state.get("moves", "")
    moves_played = len(moves_str.split()) if moves_str else 0
    moves_remaining = max(10, ESTIMATED_GAME_MOVES - moves_played // 2)

    # Distribute remaining time across the estimated moves and add a portion
    # of the increment.  This tends to spend more time early in the game while
    # still safeguarding against time trouble in longer games.
    think = (remaining / moves_remaining) + 0.8 * increment

    return max(MOVE_TIME, min(think, max(0.2, remaining - 0.1)))


def safe_make_move(client: berserk.Client, game_id: str, uci: str) -> bool:
    """Attempt to post a move.

    Returns ``True`` if the move was accepted by Lichess.  If the move is
    rejected (e.g. game already over or not our turn) or if we exhaust retry
    attempts due to transient errors, a warning is logged and ``False`` is
    returned so the caller may decide how to proceed.
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

            # Non-fatal: invalid move due to game state
            if ("Not your turn" in msg) or ("game already over" in msg) or status in (400, 409):
                print(f"[warn] Move rejected by server (not our turn / game over). uci={uci}")
                return False

            # Game gone; treat as finished
            if status in (404, 410):
                print(f"[warn] Game no longer available (HTTP {status}).")
                return False

            transient_markers = (
                "Remote end closed connection without response",
                "Connection aborted",
                "Read timed out",
                "timed out",
                "502", "503", "504", "429",
            )
            if any(t in msg for t in transient_markers):
                if attempt < SEND_RETRIES:
                    time.sleep(delay)
                    delay *= 1.5
                    last_err = msg
                    continue

            raise

    print(f"[warn] Failed to send move after {SEND_RETRIES} attempts: {last_err}")
    return False


def play_game(client: berserk.Client, engine: chess.engine.SimpleEngine, game_id: str, me_id: str) -> chess.engine.SimpleEngine:
    stream = client.bots.stream_game_state(game_id)

    my_color_is_white = None
    board = chess.Board()
    ponder_move = None

    for event in stream:
        t = event.get("type")

        if t == "gameFull":
            white_id = event["white"]["id"]
            black_id = event["black"]["id"]
            my_color_is_white = (white_id == me_id)
            state = event.get("state", {})
            moves = state.get("moves", "")
            board = chess.Board()
            if moves:
                for mv in moves.split():
                    board.push_uci(mv)

            if is_my_turn(board, my_color_is_white) and not board.is_game_over():
                think_time = calc_move_time(state, my_color_is_white)
                try:
                    result = engine.play(board, chess.engine.Limit(time=think_time), ponder=True)
                    ponder_move = result.ponder
                    move_sent = safe_make_move(client, game_id, result.move.uci())
                    if not move_sent:
                        print("[warn] Move send failed, retrying once")
                        safe_make_move(client, game_id, result.move.uci())
                except (asyncio.TimeoutError,
                        chess.engine.EngineError,
                        chess.engine.EngineTerminatedError) as e:
                    print(f"[error] engine.play failed: {e}")
                    try:
                        engine.stop()
                    except Exception:
                        pass
                    try:
                        engine = start_engine()
                    except Exception as ex:
                        print(f"[error] could not restart engine: {ex}")
                    moves = list(board.legal_moves)
                    if moves:
                        fallback = random.choice(moves)
                        move_sent = safe_make_move(client, game_id, fallback.uci())
                        if not move_sent:
                            print("[warn] Fallback move send failed, retrying once")
                            safe_make_move(client, game_id, fallback.uci())
                    ponder_move = None

        elif t == "gameState":
            state = event
            moves = state.get("moves", "")
            board = chess.Board()
            last_move = None
            if moves:
                mlist = moves.split()
                for mv in mlist:
                    board.push_uci(mv)
                if mlist:
                    last_move = chess.Move.from_uci(mlist[-1])

            # Check after applying moves
            if board.is_game_over():
                continue

            if my_color_is_white is None:
                continue

            if is_my_turn(board, my_color_is_white) and not board.is_game_over():
                if ponder_move:
                    try:
                        if last_move == ponder_move:
                            engine.ponderhit(board)
                        else:
                            engine.stop()
                    except Exception:
                        pass
                    ponder_move = None

                think_time = calc_move_time(state, my_color_is_white)
                try:
                    result = engine.play(board, chess.engine.Limit(time=think_time), ponder=True)
                    ponder_move = result.ponder
                    move_sent = safe_make_move(client, game_id, result.move.uci())
                    if not move_sent:
                        print("[warn] Move send failed, retrying once")
                        safe_make_move(client, game_id, result.move.uci())
                except (asyncio.TimeoutError,
                        chess.engine.EngineError,
                        chess.engine.EngineTerminatedError) as e:
                    print(f"[error] engine.play failed: {e}")
                    try:
                        engine.stop()
                    except Exception:
                        pass
                    try:
                        engine = start_engine()
                    except Exception as ex:
                        print(f"[error] could not restart engine: {ex}")
                    moves = list(board.legal_moves)
                    if moves:
                        fallback = random.choice(moves)
                        move_sent = safe_make_move(client, game_id, fallback.uci())
                        if not move_sent:
                            print("[warn] Fallback move send failed, retrying once")
                            safe_make_move(client, game_id, fallback.uci())
                    ponder_move = None

        elif t == "chatLine":
            username = event.get("username")
            text = event.get("text")
            print(f"[chat] {username}: {text}")

        elif t == "gameFinish":
            print(f"[*] Game finished: {game_id}")
            break

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

def find_similar_bots(client: berserk.Client,
                      my_username: str,
                      tc_type: str = "blitz",
                      delta: int = 100,
                      max_candidates: int = 5,
                      exclude: Optional[List[str]] = None) -> List[str]:
    exclude = set(exclude or [])
    perf_key = PERF_FOR_TC.get(tc_type, "blitz")

    # My rating
    me = client.users.get_public_data(my_username)
    my_rating, _ = _get_perf_rating(me, perf_key)
    if not my_rating:
        print(f"[-] No {perf_key} rating for {my_username}; falling back to 1200.")
        my_rating = 1200

    # Get currently online bots (API naming differs across berserk versions)
    bots = []
    try:
        bots = list(client.bots.get_online_bots())
    except AttributeError:
        try:
            bots = list(client.users.get_online_bots())  # rare fallback
        except Exception:
            pass

    ids = [b.get("id") for b in bots if b.get("id") and b["id"] != my_username and b["id"] not in exclude]

    matches = []
    for uid in ids:
        try:
            u = client.users.get_public_data(uid)
            r, prov = _get_perf_rating(u, perf_key)
            if r and not prov and abs(r - my_rating) <= delta:
                matches.append((uid, r))
        except Exception:
            continue
        time.sleep(0.2)  # be polite

    matches.sort(key=lambda t: abs(t[1] - my_rating))
    return [u for (u, _) in matches[:max_candidates]]

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


def outbound_challenge_loop(stop_event: threading.Event,
                            my_username: str,
                            base_token: str,
                            active_counter: ActiveCounter):
    """
    Periodically find similar-rated online bots and challenge a few.
    IDLE-ONLY: will NOT challenge while any game is active.
    """
    if not ENABLE_OUTBOUND_CHALLENGES:
        return

    while not stop_event.is_set():
        try:
            # IDLE-ONLY: do nothing if we're playing any game
            if active_counter.get() != 0:
                # sleep the whole period; try again later
                for _ in range(OUTBOUND_PERIOD_SEC):
                    if stop_event.is_set():
                        return
                    time.sleep(1)
                continue

            session = make_hardened_session(base_token)
            client = berserk.Client(session=session)

            targets = find_similar_bots(
                client,
                my_username=my_username,
                tc_type=OUTBOUND_TC,
                delta=RATING_DELTA,
                max_candidates=OUTBOUND_MAX_PER_CYCLE,
            )

            for opp in targets:
                # still idle?
                if active_counter.get() != 0:
                    break
                challenge_user(
                    client,
                    opponent=opp,
                    rated=OUTBOUND_RATED,
                    clock_limit=OUTBOUND_CLOCK_LIMIT,
                    clock_increment=OUTBOUND_INCREMENT,
                    color="random",
                )
                time.sleep(1.0)

        except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
            status = getattr(getattr(e, "response", None), "status_code", None)
            if status == 429:
                retry_after = None
                resp = getattr(e, "response", None)
                if resp is not None:
                    retry_after = resp.headers.get("Retry-After")
                delay = int(retry_after) if retry_after and retry_after.isdigit() else 60
                print(f"[warn] outbound loop rate limited (HTTP 429). Sleeping {delay}s")
                for _ in range(delay):
                    if stop_event.is_set():
                        return
                    time.sleep(1)
                continue
            else:
                print(f"[warn] outbound loop error: {e}")
        except Exception as e:
            print(f"[warn] outbound loop error: {e}")

        # Sleep between cycles
        for _ in range(OUTBOUND_PERIOD_SEC):
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
        if status in (400, 404, 410):
            print(f"[warn] Challenge {chal_id} vanished before accept (HTTP {status}).")
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
                variant = chal["variant"]["key"]               # "standard", "chess960", ...
                tc_type = chal["timeControl"]["type"]          # "bullet","blitz","rapid","classical","correspondence"
                rated = chal.get("rated", False)
                challenger = chal["challenger"]["name"]

                print(f"[event] Challenge from {challenger} | variant={variant} | tc={tc_type} | rated={rated}")

                accept = True
                if variant not in ACCEPT_VARIANTS:
                    accept = False
                elif rated and not ALLOW_RATED:
                    accept = False
                elif (not rated) and not ALLOW_CASUAL:
                    accept = False
                elif (tc_type == "correspondence") and not ALLOW_CORRESPONDENCE:
                    accept = False
                elif tc_type not in ACCEPT_TC_TYPES:
                    accept = False
                elif active_counter.get() >= MAX_CONCURRENT_GAMES:
                    accept = False

                if accept:
                    safe_accept_challenge(client, chal_id)
                else:
                    safe_decline_challenge(client, chal_id, reason="variant")

            elif et == "challengeCanceled":
                chal = event.get("challenge", {})
                print(f"[event] Challenge canceled: {chal.get('id','?')}")

            elif et == "challengeDeclined":
                chal = event.get("challenge", {})
                print(f"[event] Challenge declined (by other side?): {chal.get('id','?')}")

            elif et == "gameStart":
                game_id = event["game"]["id"]
                print(f"[event] Game start: {game_id}")
                active_counter.inc()
                engine = play_game(client, engine, game_id, me_id)

            elif et == "gameFinish":
                # Top-level finish notices; keep the counter accurate
                active_counter.dec()

    except KeyboardInterrupt:
        print("\n[+] Shutting down (Ctrl+C)")
    finally:
        try:
            engine.quit()
        except Exception:
            pass
        stop_event.set()
        try:
            thread.join(timeout=3.0)
        except Exception:
            pass


if __name__ == "__main__":
    if platform.system().lower() == "windows":
        import msvcrt  # noqa: F401
    run_bot()
