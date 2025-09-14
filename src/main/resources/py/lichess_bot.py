#!/usr/bin/env python3
import berserk
import chess
import chess.engine
import os
import sys
import time

# ==========================
# CONFIGURATION
# ==========================
LICHESS_TOKEN = os.environ.get("LICHESS_TOKEN") or "YOUR_TOKEN_HERE"

ENGINE_PATH = "/home/julius/engines/myengine"  # <-- adjust this
ENGINE_OPTIONS = {
    "Threads": 4,
    "Hash": 256
}

# ==========================
# CONNECT TO LICHESS
# ==========================
session = berserk.TokenSession(LICHESS_TOKEN)
client = berserk.Client(session=session)

print("[+] Connected to Lichess as:", client.account.get()["username"])

# ==========================
# LAUNCH UCI ENGINE
# ==========================
try:
    engine = chess.engine.SimpleEngine.popen_uci(ENGINE_PATH)
    for name, value in ENGINE_OPTIONS.items():
        try:
            engine.configure({name: value})
        except Exception:
            pass
    print("[+] Engine started:", ENGINE_PATH)
except FileNotFoundError:
    print("[-] Engine not found at", ENGINE_PATH)
    sys.exit(1)

# ==========================
# MAIN GAME LOOP
# ==========================
def play_game(game_id):
    board = chess.Board()
    stream = client.bots.stream_game_state(game_id)

    for event in stream:
        if event["type"] == "gameFull":
            state = event["state"]
            moves = state.get("moves", "").split()
            for move in moves:
                board.push_uci(move)

        if event["type"] == "gameState":
            moves = event["moves"].split()
            board = chess.Board()
            for move in moves:
                board.push_uci(move)

            if (board.turn and event["white"]["id"] == client.account.get()["id"]) or \
                    (not board.turn and event["black"]["id"] == client.account.get()["id"]):
                # Bot's turn
                with engine.analysis(board, limit=chess.engine.Limit(time=0.5)) as analysis:
                    for info in analysis:
                        if "pv" in info:
                            best_move = info["pv"][0]
                            break
                print(f"[+] Playing {best_move}")
                client.bots.make_move(game_id, best_move.uci())

        if event["type"] == "chatLine":
            print("[chat]", event["username"], ":", event["text"])

    print("[*] Game finished:", game_id)


# ==========================
# CHALLENGE HANDLING
# ==========================
def run_bot():
    print("[+] Waiting for challenges...")
    for event in client.bots.stream_incoming_events():
        if event["type"] == "challenge":
            chal = event["challenge"]
            challenger = chal["challenger"]["id"]
            variant = chal["variant"]["key"]
            tc = chal["timeControl"]["type"]

            print(f"[+] Challenge from {challenger} ({variant}, {tc})")

            if variant == "standard":
                client.bots.accept_challenge(chal["id"])
                print("[+] Accepted challenge")
            else:
                client.bots.decline_challenge(chal["id"], reason="variant")
                print("[-] Declined non-standard challenge")

        elif event["type"] == "gameStart":
            game_id = event["game"]["id"]
            print("[+] Game started:", game_id)
            play_game(game_id)


if __name__ == "__main__":
    try:
        run_bot()
    except KeyboardInterrupt:
        print("Exiting...")
    finally:
        engine.quit()
