#!/usr/bin/env python3
import os
import sys
import glob
import re
import time
import shlex
import platform
from pathlib import Path

import berserk
import chess
import chess.engine

# $env:LICHESS_TOKEN="paste_your_token_here"
# $env:CHESSENGINE_THREADS=$env:NUMBER_OF_PROCESSORS
# $env:CHESSENGINE_ROOT_PAR_LIMIT="128"


# ========= USER CONFIG (edit these) =========
# Prefer a .bat if you have one. Otherwise leave ENGINE_BAT empty and set JAR_DIR.
ENGINE_BAT = ""  # e.g. r"E:\ChessEngines\chess-engine-3.0.9.bat"
JAR_DIR = r"C:\Development\Chess-Engine\target"  # where chess-engine-*-uci.jar is built
JAVA_EXE = r"java"  # or full path to java.exe if needed

# Java system properties mirrored from your .bat
ROOT_PAR_LIMIT = os.environ.get("CHESSENGINE_ROOT_PAR_LIMIT", "128")
SEARCH_THREADS = os.environ.get("CHESSENGINE_THREADS") or os.environ.get("NUMBER_OF_PROCESSORS") or "4"

# Lichess auth
LICHESS_TOKEN = os.environ.get("LICHESS_TOKEN") or "YOUR_TOKEN_HERE"

# Engine thinking time per move (seconds)
MOVE_TIME = float(os.environ.get("BOT_MOVE_TIME", "0.5"))

# Accept/decline policy
ACCEPT_VARIANTS = {"standard"}  # only accept standard


# ===========================================


def fail(msg: str):
    print(msg, file=sys.stderr)
    sys.exit(1)


def find_latest_uci_jar(dir_path: str) -> Path:
    """
    Find the latest chess-engine-*-uci.jar by semantic version in dir_path.
    Matches chess-engine-X.Y.Z-uci.jar (or more segments X.Y.Z.W).
    """
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


def build_engine_cmd() -> list:
    """
    Build a subprocess command list to launch the UCI engine on Windows:
    - If ENGINE_BAT exists -> run via cmd /c <bat>
    - Else find latest *-uci.jar in JAR_DIR and construct: java -D... -jar <jar> <UciMain (if needed)>
      (Your jar already exposes UCI via main class; no extra args should be needed.)
    """
    # Prefer the .bat if present
    if ENGINE_BAT and Path(ENGINE_BAT).exists():
        # Launch .bat through cmd to ensure proper execution on Windows
        return ["cmd", "/c", ENGINE_BAT]

    # Otherwise try the newest jar
    if not JAR_DIR or not Path(JAR_DIR).exists():
        fail(f"[-] Neither ENGINE_BAT found nor valid JAR_DIR: {JAR_DIR}")

    jar = find_latest_uci_jar(JAR_DIR)
    if not jar:
        fail(f"[-] No chess-engine-*-uci.jar found in {JAR_DIR}")

    # Mirror your .bat’s Java opts
    java_opts = [
        f"-Dchessengine.searchThreads={SEARCH_THREADS}",
        f"-Dchessengine.rootParallelLimit={ROOT_PAR_LIMIT}",
        "-Dlogging.level.root=ERROR",
    ]

    # If your UCI main class must be specified as argument after jar, add it here.
    # Your .bat calls: `-jar chess-engine-*-uci.jar julius.game.chessengine.uci.UciMain`
    # If your jar requires that class name, keep it; if the jar's Main-Class handles UCI already, you can drop it.
    return [JAVA_EXE, *java_opts, "-jar", str(jar), "julius.game.chessengine.uci.UciMain"]


def connect_lichess():
    if not LICHESS_TOKEN or LICHESS_TOKEN == "YOUR_TOKEN_HERE":
        fail("[-] LICHESS_TOKEN not set. Set $env:LICHESS_TOKEN in PowerShell or hardcode it in the script.")

    session = berserk.TokenSession(LICHESS_TOKEN)
    client = berserk.Client(session=session)
    me = client.account.get()
    username = me.get("username", "<unknown>")
    user_id = me.get("id")
    print(f"[+] Connected to Lichess as: {username}")
    return client, username, user_id


def start_engine():
    cmd = build_engine_cmd()
    print("[+] Engine command:", " ".join(shlex.quote(x) for x in cmd))
    try:
        # python-chess handles Popen internally; we pass a list command
        engine = chess.engine.SimpleEngine.popen_uci(cmd)
    except FileNotFoundError as e:
        fail(f"[-] Could not start engine. File not found: {e}")
    except Exception as e:
        fail(f"[-] Could not start engine: {e}")

    # You can configure UCI options here if your engine supports them via UCI (separate from -D props)
    # Example:
    # try:
    #     engine.configure({"Threads": int(SEARCH_THREADS), "Hash": 256})
    # except Exception:
    #     pass

    print("[+] Engine started successfully")
    return engine


def is_my_turn(board: chess.Board, my_color_is_white: bool) -> bool:
    return (board.turn is chess.WHITE) == my_color_is_white


def calc_move_time(state: dict, my_color_is_white: bool) -> float:
    """Compute a thinking time based on remaining clock and increment.

    Falls back to MOVE_TIME if clock data is unavailable. The engine will
    use a small portion of the remaining time plus most of the increment,
    which slows play in longer time controls like 3/2.
    """
    key_time = "wtime" if my_color_is_white else "btime"
    key_inc = "winc" if my_color_is_white else "binc"
    time_ms = state.get(key_time)
    inc_ms = state.get(key_inc, 0)

    if time_ms is None:
        return MOVE_TIME

    remaining = time_ms / 1000.0
    increment = inc_ms / 1000.0 if inc_ms else 0.0

    # use a small fraction of remaining time plus most of the increment,
    # but never exceed the remaining time
    think = remaining / 100.0 + 0.8 * increment
    think = max(MOVE_TIME, min(think, remaining - 0.1))
    return think


def play_game(client: berserk.Client, engine: chess.engine.SimpleEngine, game_id: str, me_id: str):
    stream = client.bots.stream_game_state(game_id)

    # We need to know which color we are; capture from gameFull
    my_color_is_white = None
    board = chess.Board()
    ponder_move = None

    for event in stream:
        t = event.get("type")

        if t == "gameFull":
            # initial snapshot
            white_id = event["white"]["id"]
            black_id = event["black"]["id"]
            my_color_is_white = (white_id == me_id)
            state = event.get("state", {})
            moves = state.get("moves", "")
            board = chess.Board()
            if moves:
                for mv in moves.split():
                    board.push_uci(mv)

            # If it's already our turn at start, move
            if is_my_turn(board, my_color_is_white):
                think_time = calc_move_time(state, my_color_is_white)
                result = engine.play(board, chess.engine.Limit(time=think_time), ponder=True)
                ponder_move = result.ponder
                client.bots.make_move(game_id, result.move.uci())
                # print eval info if available
                # print(f"[move] {result.move}")

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

            if my_color_is_white is None:
                # Shouldn't happen after gameFull, but be defensive
                continue

            if is_my_turn(board, my_color_is_white) and not board.is_game_over():
                # If the engine was pondering and predicted correctly, let it know
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
                client.bots.make_move(game_id, result.move.uci())
                # print(f"[move] {result.move}")

        elif t == "chatLine":
            # Optional: respond or log
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
        # Make sure stdout flushes promptly in PowerShell
        import msvcrt  # noqa: F401
    run_bot()
