import argparse
import os
import re
import subprocess
import time
from typing import Optional, Dict, Any

import requests

#py -3 EngineBattle.py --jar1 E:/ChessEngines/chess-engine-3.0.0.jar --jar2 E:/ChessEngines/chess-engine-3.0.1.jar

# ----------------------------
# Utilities
# ----------------------------

def find_latest_jar(directory_path: str) -> Optional[str]:
    """
    Return the newest chess-engine-<semver>.jar filename (not full path) from directory_path,
    or None if none found.
    """
    if not os.path.isdir(directory_path):
        return None
    jar_files = [
        f for f in os.listdir(directory_path)
        if f.endswith('.jar') and re.match(r'chess-engine-\d+\.\d+\.\d+\.jar', f)
    ]
    # Sort by semantic version if present; name sort descending is usually fine when names encode semver.
    jar_files.sort(reverse=True)
    return jar_files[0] if jar_files else None


def get_file_name_without_extension(file_path: str) -> str:
    base_name = os.path.basename(file_path)
    return os.path.splitext(base_name)[0]


def start_java_process(jar_path: str, port: int) -> subprocess.Popen:
    """
    Start the Spring Boot JAR on a given port with stdout/stderr suppressed.
    """
    if not os.path.isfile(jar_path):
        raise FileNotFoundError(f"JAR not found: {jar_path}")
    with open(os.devnull, 'w') as devnull:
        return subprocess.Popen(
            ['java', '-jar', jar_path, f'--server.port={port}'],
            stdout=devnull, stderr=devnull
        )


def is_server_running(base_url: str, timeout_s: float = 1.5) -> bool:
    """
    Consider the server up if either /actuator/health (if present) or / responds with HTTP 200.
    """
    try:
        # Prefer actuator if enabled
        r = requests.get(base_url.rstrip('/') + '/actuator/health', timeout=timeout_s)
        if r.status_code == 200:
            return True
    except requests.RequestException:
        pass
    try:
        r = requests.get(base_url, timeout=timeout_s)
        return r.status_code == 200
    except requests.RequestException:
        return False


def wait_until_up(base_url: str, deadline_s: float) -> bool:
    """
    Poll until the server responds OK or deadline passes.
    """
    end = time.time() + deadline_s
    while time.time() < end:
        if is_server_running(base_url):
            return True
        time.sleep(0.3)
    return False


# ----------------------------
# Engine API helpers
# ----------------------------

def make_move(engine_url: str, frm: str, to: str, timeout_s: float = 3.0) -> Dict[str, Any]:
    r = requests.patch(f"{engine_url}/chess/figure/move/{frm}/{to}", timeout=timeout_s)
    r.raise_for_status()
    return r.json()


def set_time_limit(engine_url: str, time_limit: int, timeout_s: float = 3.0) -> bool:
    r = requests.patch(f"{engine_url}/chess/autoplay/timelimit/{time_limit}", timeout=timeout_s)
    return r.ok


def start_autoplay(engine_url: str, ai_color: str, timeout_s: float = 3.0) -> bool:
    r = requests.patch(f"{engine_url}/chess/autoplay/{ai_color}", timeout=timeout_s)
    return r.ok


def get_last_move(engine_url: str, timeout_s: float = 3.0) -> Optional[Dict[str, Any]]:
    r = requests.get(f"{engine_url}/chess/autoplay/lastMove", timeout=timeout_s)
    if r.ok:
        try:
            return r.json()
        except ValueError:
            return None
    return None


def reset_board(engine_url: str, timeout_s: float = 5.0) -> bool:
    r = requests.put(f"{engine_url}/chess/reset", timeout=timeout_s)
    return r.ok


def check_game_state(state: Optional[str]) -> Optional[str]:
    if state == "WHITE_WON":
        return "white"
    if state == "BLACK_WON":
        return "black"
    if state == "DRAW":
        return "draw"
    return None


# ----------------------------
# Match runner
# ----------------------------

def run_matches(jar1_path: str,
                jar2_path: str,
                port1: int = 8080,
                port2: int = 8082,
                games: int = 100,
                engine_time_limit: int = 50,
                move_timeout_s: float = 3.0,
                startup_deadline_s: float = 30.0,
                poll_sleep_s: float = 0.05) -> None:
    """
    Launch two engines and have them play N games by copying moves between them.
    """
    engine1_url = f"http://localhost:{port1}"
    engine2_url = f"http://localhost:{port2}"

    print(f"Launching engines:\n  {jar1_path} -> {engine1_url}\n  {jar2_path} -> {engine2_url}")

    engine1 = start_java_process(jar1_path, port1)
    engine2 = start_java_process(jar2_path, port2)

    try:
        # Wait until both servers are up (faster and more reliable than fixed sleep)
        if not wait_until_up(engine1_url, startup_deadline_s):
            raise RuntimeError(f"Engine 1 did not come up at {engine1_url} within {startup_deadline_s}s")
        if not wait_until_up(engine2_url, startup_deadline_s):
            raise RuntimeError(f"Engine 2 did not come up at {engine2_url} within {startup_deadline_s}s")

        print("Both servers are running. Ready to play.")

        engine1_wins = engine2_wins = draws = 0

        for game_number in range(1, games + 1):
            print(f"\n=== Starting game {game_number} ===")

            # Reset boards to a clean start each game
            if not (reset_board(engine1_url) and reset_board(engine2_url)):
                print(f"Failed to reset one of the boards for game {game_number}; aborting.")
                break

            # Reset per-game trackers
            last_move_made_by_engine1 = None
            last_move_made_by_engine2 = None
            last_move_time_engine1 = time.time()
            last_move_time_engine2 = time.time()

            color_engine1, color_engine2 = ("WHITE", "BLACK") if game_number % 2 == 1 else ("BLACK", "WHITE")

            # Configure engine search time and start autoplay for both
            if not (set_time_limit(engine1_url, engine_time_limit) and set_time_limit(engine2_url, engine_time_limit)):
                print(f"Failed to set time limits for game {game_number}; aborting.")
                break

            if not (start_autoplay(engine1_url, color_engine1) and start_autoplay(engine2_url, color_engine2)):
                print(f"Failed to start autoplay for game {game_number}; aborting.")
                break

            try:
                # Game loop
                while True:
                    # Poll engine 1
                    last_move_engine1 = get_last_move(engine1_url)
                    if last_move_engine1 and last_move_engine1 != last_move_made_by_engine1:
                        last_move_made_by_engine1 = last_move_engine1
                        last_move_time_engine1 = time.time()
                        if 'from' in last_move_engine1 and 'to' in last_move_engine1:
                            make_move(engine2_url, last_move_engine1['from'], last_move_engine1['to'])

                    # Poll engine 2
                    last_move_engine2 = get_last_move(engine2_url)
                    if last_move_engine2 and last_move_engine2 != last_move_made_by_engine2:
                        last_move_made_by_engine2 = last_move_engine2
                        last_move_time_engine2 = time.time()
                        if 'from' in last_move_engine2 and 'to' in last_move_engine2:
                            make_move(engine1_url, last_move_engine2['from'], last_move_engine2['to'])

                    # Move timeouts (engine failed to reply within move_timeout_s)
                    now = time.time()
                    if now - last_move_time_engine1 > move_timeout_s:
                        engine2_wins += 1
                        print(f"{get_file_name_without_extension(jar1_path)} failed to move in time.")
                        break
                    if now - last_move_time_engine2 > move_timeout_s:
                        engine1_wins += 1
                        print(f"{get_file_name_without_extension(jar2_path)} failed to move in time.")
                        break

                    # Game end by state
                    game_state = None
                    if last_move_engine1 and 'currentState' in last_move_engine1:
                        game_state = check_game_state(last_move_engine1['currentState'])
                    elif last_move_engine2 and 'currentState' in last_move_engine2:
                        game_state = check_game_state(last_move_engine2['currentState'])

                    if game_state:
                        if color_engine1 == "WHITE":
                            if game_state == "white":
                                engine1_wins += 1
                            elif game_state == "black":
                                engine2_wins += 1
                        else:
                            if game_state == "white":
                                engine2_wins += 1
                            elif game_state == "black":
                                engine1_wins += 1
                        if game_state == "draw":
                            draws += 1
                        break

                    time.sleep(poll_sleep_s)  # be kind to the CPU

            except KeyboardInterrupt:
                print("Game interrupted by user.")
                break

            print(f"{get_file_name_without_extension(jar1_path)} wins: {engine1_wins}, "
                  f"{get_file_name_without_extension(jar2_path)} wins: {engine2_wins}, "
                  f"Draws: {draws}")

    finally:
        # Ensure both engines are stopped
        for proc, label in [(engine1, "engine1"), (engine2, "engine2")]:
            if proc and proc.poll() is None:
                try:
                    proc.terminate()
                    proc.wait(timeout=5)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
        print("Chess engines terminated.")


# ----------------------------
# CLI
# ----------------------------

def main():
    parser = argparse.ArgumentParser(description="Play head-to-head matches between two HTTP chess engines.")
    parser.add_argument("--jar1", default="D:/Chess-Engines/v2/chess-engine-2.9.0.jar",
                        help="Path to reference engine JAR (v2).")
    parser.add_argument("--jar2", default=None,
                        help="Path to new engine JAR (defaults to latest chess-engine-*.jar in ./target)")
    parser.add_argument("--target-dir", default="target", help="Directory to search for newest engine JAR.")
    parser.add_argument("--port1", type=int, default=8080)
    parser.add_argument("--port2", type=int, default=8082)
    parser.add_argument("--games", type=int, default=100)
    parser.add_argument("--engine-time-limit", type=int, default=50,
                        help="Time limit configured on the engine (seconds per move/search window).")
    parser.add_argument("--move-timeout", type=float, default=3.0,
                        help="Wall-clock tolerance before declaring the other engine wins.")
    parser.add_argument("--startup-deadline", type=float, default=30.0,
                        help="Seconds to wait for each server to become reachable.")
    args = parser.parse_args()

    # Resolve jar2 if not provided
    jar2_path = args.jar2
    if jar2_path is None:
        latest = find_latest_jar(args.target_dir)
        if not latest:
            raise SystemExit(f"No JAR file found in {args.target_dir} matching chess-engine-<semver>.jar")
        jar2_path = os.path.join(args.target_dir, latest)
        print(f"Using latest JAR in {args.target_dir}: {jar2_path}")

    run_matches(
        jar1_path=args.jar1,
        jar2_path=jar2_path,
        port1=args.port1,
        port2=args.port2,
        games=args.games,
        engine_time_limit=args.engine_time_limit,
        move_timeout_s=args.move_timeout,
        startup_deadline_s=args.startup_deadline
    )


if __name__ == "__main__":
    main()
