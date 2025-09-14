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

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import berserk
import chess
import chess.engine

# ================== USER CONFIG ==================
ENGINE_BAT = ""  # e.g. r"E:\ChessEngines\chess-engine-3.0.9.bat"
JAR_DIR = r"C:\Development\Chess-Engine\target"
JAVA_EXE = r"java"

ROOT_PAR_LIMIT = os.environ.get("CHESSENGINE_ROOT_PAR_LIMIT", "128")
SEARCH_THREADS = (
        os.environ.get("CHESSENGINE_THREADS")
        or os.environ.get("NUMBER_OF_PROCESSORS")
        or "4"
)

LICHESS_TOKEN = os.environ.get("LICHESS_TOKEN") or "YOUR_TOKEN_HERE"

MOVE_TIME = float(os.environ.get("BOT_MOVE_TIME", "0.5"))

ACCEPT_VARIANTS = {"standard"}

# Network hardening
HTTP_TOTAL_RETRIES = int(os.environ.get("HTTP_TOTAL_RETRIES", "5"))
HTTP_BACKOFF = float(os.environ.get("HTTP_BACKOFF", "0.5"))
HTTP_POOL_MAXSIZE = int(os.environ.get("HTTP_POOL_MAXSIZE", "20"))
HTTP_TIMEOUT = float(os.environ.get("HTTP_TIMEOUT", "10"))  # seconds
SEND_RETRIES = int(os.environ.get("SEND_RETRIES", "3"))
DISABLE_PROXIES = os.environ.get("DISABLE_PROXIES", "0") == "1"

USER_AGENT = os.environ.get("BOT_USER_AGENT", "JuliusChessBot/0.1")
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
    """
    Mount adapters on either:
    - a requests.Session-like object (has .mount), or
    - a wrapper exposing .session.mount
    """
    if hasattr(obj, "mount"):
        obj.mount("https://", adapter)
        obj.mount("http://", adapter)
        return
    inner = getattr(obj, "session", None)
    if inner is not None and hasattr(inner, "mount"):
        inner.mount("https://", adapter)
        inner.mount("http://", adapter)
        return
    # If neither exists, we skip mounting (very unusual); retries will still happen via server errors.


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
    _set_trust_env(ts, not DISABLE_PROXIES)  # False ignores system proxies

    # Inject a default timeout into every API call (don’t rely on Client(..., timeout=...))
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
    client = berserk.Client(session=session)  # no 'timeout' kwarg for this berserk version
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

    think = remaining / 100.0 + 0.8 * increment
    return max(MOVE_TIME, min(think, max(0.2, remaining - 0.1)))


def safe_make_move(client: berserk.Client, game_id: str, uci: str) -> None:
    """
    Try to post a move. Swallow non-fatal server rejections like:
      - game already over
      - not our turn
    Retry briefly on transient network issues. Never crash the bot.
    """
    delay = 0.4
    last_err = None

    for attempt in range(1, SEND_RETRIES + 1):
        try:
            client.bots.make_move(game_id, uci)
            return

        except (berserk.exceptions.ResponseError, berserk.exceptions.ApiError) as e:
            msg = str(e)
            status = getattr(getattr(e, "response", None), "status_code", None)

            # --- Non-fatal: game state made the move invalid ---
            if ("Not your turn" in msg) or ("game already over" in msg) or status in (400, 409):
                print(f"[warn] Move rejected by server (not our turn / game over). uci={uci}")
                return

            # --- Game vanished/aborted; treat as finished ---
            if status in (404, 410):
                print(f"[warn] Game no longer available (HTTP {status}).")
                return

            # --- Likely transient network errors: back off and retry ---
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

            # --- Anything else: re-raise so we notice genuine bugs ---
            raise

    # If adapter-level retries and our loop both exhausted:
    raise berserk.exceptions.ApiError(last_err or "Unknown error when sending move")



def play_game(client: berserk.Client, engine: chess.engine.SimpleEngine, game_id: str, me_id: str):
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

            if is_my_turn(board, my_color_is_white):
                think_time = calc_move_time(state, my_color_is_white)
                result = engine.play(board, chess.engine.Limit(time=think_time), ponder=True)
                ponder_move = result.ponder
                safe_make_move(client, game_id, result.move.uci())

        elif t == "gameState":
            state = event
            moves = state.get("moves", "")
            board = chess.Board()
            if board.is_game_over():
                # No more moves; wait for 'gameFinish' to arrive and break there.
                continue
            last_move = None
            if moves:
                mlist = moves.split()
                for mv in mlist:
                    board.push_uci(mv)
                if mlist:
                    last_move = chess.Move.from_uci(mlist[-1])

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
                result = engine.play(board, chess.engine.Limit(time=think_time), ponder=True)
                ponder_move = result.ponder
                safe_make_move(client, game_id, result.move.uci())

        elif t == "chatLine":
            username = event.get("username")
            text = event.get("text")
            print(f"[chat] {username}: {text}")

        elif t == "gameFinish":
            print(f"[*] Game finished: {game_id}")
            break


def run_bot():
    client, username, me_id = connect_lichess()
    engine = start_engine()
    print("[+] Waiting for challenges... (accepting: standard)")

    try:
        for event in client.bots.stream_incoming_events():
            et = event.get("type")

            if et == "challenge":
                chal = event["challenge"]
                chal_id = chal["id"]
                variant = chal["variant"]["key"]
                tc_type = chal["timeControl"]["type"]
                rated = chal.get("rated", False)
                challenger = chal["challenger"]["name"]

                print(f"[event] Challenge from {challenger} | {variant} | {tc_type} | rated={rated}")

                if variant in ACCEPT_VARIANTS:
                    client.bots.accept_challenge(chal_id)
                    print("[+] Accepted challenge")
                else:
                    client.bots.decline_challenge(chal_id, reason="variant")
                    print("[-] Declined challenge (variant)")

            elif et == "gameStart":
                game_id = event["game"]["id"]
                print(f"[event] Game start: {game_id}")
                play_game(client, engine, game_id, me_id)

    except KeyboardInterrupt:
        print("\n[+] Shutting down (Ctrl+C)")
    finally:
        try:
            engine.quit()
        except Exception:
            pass


if __name__ == "__main__":
    if platform.system().lower() == "windows":
        import msvcrt  # noqa: F401
    run_bot()
