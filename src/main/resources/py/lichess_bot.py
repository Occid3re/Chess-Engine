#!/usr/bin/env python3
import berserk
import chess
import chess.engine
import os
import sys

# ==========================
# CONFIGURATION
# ==========================
LICHESS_TOKEN = os.environ.get("LICHESS_TOKEN") or "YOUR_TOKEN_HERE"

# Path to your UCI engine binary. Override with the ENGINE_PATH environment
# variable or edit the default below.
ENGINE_PATH = os.environ.get("ENGINE_PATH", "/home/julius/engines/myengine")
ENGINE_OPTIONS = {
    "Threads": 4,
    "Hash": 256
}

# ==========================
# CONNECT TO LICHESS
# ==========================
session = berserk.TokenSession(LICHESS_TOKEN)
client = berserk.Client(session=session)

account_info = client.account.get()
BOT_ID = account_info["id"]
print("[+] Connected to Lichess as:", account_info["username"])

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
    white_id = black_id = None

    for event in stream:
        if event["type"] == "gameFull":
            white_id = event["white"]["id"]
            black_id = event["black"]["id"]
            state = event["state"]
            moves = state.get("moves", "").split()
            for move in moves:
                board.push_uci(move)

        elif event["type"] == "gameState":
            moves = event.get("moves", "").split()
            board = chess.Board()
            for move in moves:
                board.push_uci(move)

            if white_id and black_id:
                is_my_turn = (board.turn and white_id == BOT_ID) or (
                    not board.turn and black_id == BOT_ID
                )
                if is_my_turn:
                    result = engine.play(board, chess.engine.Limit(time=0.5))
                    best_move = result.move
                    print(f"[+] Playing {best_move}")
                    client.bots.make_move(game_id, best_move.uci())

        elif event["type"] == "chatLine":
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
