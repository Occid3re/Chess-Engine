import argparse
import os
import re
import subprocess
import time
from typing import Optional, Dict, Any

import requests


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
    jar_files.sort(reverse=True)
    return jar_files[0] if jar_files else None


def get_file_name_without_extension(file_path: str) -> str:
    base_name = os.path.basename(file_path)
    return os.path.splitext(base_name)[0]


def start_java_process(jar_path: str, port: int) -> subprocess.Popen:
    """
    Start the Spring Boot JAR on a given port with stdout/stderr suppressed.
    Uses the 'java' on PATH.
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


def format_http_error(e: requests.HTTPError) -> str:
    """
    Extract a concise, useful message from an HTTPError, including status code and any
    JSON 'message'/'detail'/'error' or first 500 chars of text/HTML (title if present).
    """
    resp = getattr(e, 'response', None)
    if not resp:
        return str(e)
    status = resp.status_code
    try:
        data = resp.json()
        detail = (
                data.get('message')
                or data.get('detail')
                or data.get('error')
        )
        if detail:
            return f"HTTP {status} — {detail}"
        # fallback: compact JSON
        import json
        return f"HTTP {status} — {json.dumps(data)[:500]}"
    except ValueError:
        text = (resp.text or "").strip()
        if not text:
            return f"HTTP {status} — <empty body>"
        # Try to extract <title> from HTML
        m = re.search(r'<title>(.*?)</title>', text, re.IGNORECASE | re.DOTALL)
        if m:
            title = re.sub(r'\s+', ' ', m.group(1)).strip()
            return f"HTTP {status} — {title}"
        return f"HTTP {status} — {text[:500]}"


def manual_award_on_timeout(engine1_name: str, engine2_name: str) -> int:
    """
    Block for manual decision after a timeout.
    Returns 1 to award Engine 1, or 2 to award Engine 2.
    """
    print("\nTIMEOUT: Paused for manual decision.")
    print(f"Type '1' to award the win to {engine1_name}, or '2' to award the win to {engine2_name}.")
    while True:
        choice = input("Award to [1/2]: ").strip()
        if choice == '1':
            return 1
        if choice == '2':
            return 2
        print("Please enter '1' or '2'.")


def fetch_json_ok(url: str, timeout_s: float = 3.0):
    try:
        r = requests.get(url, timeout=timeout_s)
        if r.status_code == 204:
            return None
        r.raise_for_status()
        return r.json()
    except requests.RequestException:
        return None


def get_fen_string(engine_url: str) -> Optional[str]:
    # Prefer new GET /chess/fen
    try:
        r = requests.get(f"{engine_url}/chess/fen", timeout=2.0)
        if r.ok:
            return r.text.strip()
    except requests.RequestException:
        pass
    # Fallback: /figure/frontend -> try to pull a field that looks like FEN
    data = fetch_json_ok(f"{engine_url}/chess/figure/frontend", timeout_s=2.5)
    if isinstance(data, dict):
        for k in ("fen", "FEN", "string", "value"):
            v = data.get(k)
            if isinstance(v, str) and "/" in v and " " in v:
                return v.strip()
        # last resort: string-ify the object
        return str(data)
    return None


def get_state(engine_url: str) -> Optional[Dict[str, Any]]:
    return fetch_json_ok(f"{engine_url}/chess/state", timeout_s=2.5)


def get_pgn_text(engine_url: str) -> Optional[str]:
    try:
        r = requests.get(f"{engine_url}/chess/pgn", timeout=3.0)
        if r.ok:
            # /chess/pgn returns a JSON object; try common fields
            try:
                j = r.json()
                for k in ("text", "pgn", "PGN", "value"):
                    if isinstance(j, dict) and isinstance(j.get(k), str):
                        return j[k].strip()
                return str(j)
            except ValueError:
                return r.text.strip()
    except requests.RequestException:
        return None
    return None


def print_timeout_diagnostics(engine_url: str, engine_name: str) -> None:
    print(f"\n--- Diagnostics for {engine_name} ---")
    state = get_state(engine_url)
    fen = get_fen_string(engine_url)
    status = fetch_json_ok(f"{engine_url}/chess/search/status", timeout_s=2.0)

    if state:
        gs = state.get("gameState") or {}
        gs_str = gs.get("state") if isinstance(gs, dict) else str(gs)
        last = state.get("lastMove")
        score = state.get("score")
        pv = state.get("move")
        print(f"GameState: {gs_str}")
        print(f"Last move: {last}")
        print(f"Score:     {score}")
        print(f"PV:        {pv}")
    else:
        print("State:     <unavailable>")

    if status:
        print("Search:    sideToMove={}".format(status.get("sideToMove")))
        print("           bestMove={}".format(status.get("bestMove")))
        print("           timeLimitMs={}".format(status.get("timeLimitMs")))
        print("           nodesVisited={}".format(status.get("nodesVisited")))
        print("           nullMoveCount={}".format(status.get("nullMoveCount")))
        if status.get("pv"):
            print("           pv={}".format(", ".join(status["pv"])))
    else:
        print("Search:    <status endpoint unavailable>")

    if fen:
        print(f"FEN:       {fen}")
    else:
        print("FEN:       <unavailable>")
    print("--- end diagnostics ---\n")


# ----------------------------
# Match runner
# ----------------------------

def run_matches(jar1_path: str,
                jar2_path: str,
                port1: int = 8080,
                port2: int = 8082,
                games: int = 100,
                engine_time_limit: int = 50,
                move_timeout_s: float = 6.0,
                startup_deadline_s: float = 60.0,
                poll_sleep_s: float = 0.05) -> None:
    engine1_url = f"http://localhost:{port1}"  # WHITE
    engine2_url = f"http://localhost:{port2}"  # BLACK
    engine1_name = get_file_name_without_extension(jar1_path)
    engine2_name = get_file_name_without_extension(jar2_path)

    print(f"Launching engines:\n  {jar1_path} -> {engine1_url}\n  {jar2_path} -> {engine2_url}")

    e1 = start_java_process(jar1_path, port1)
    e2 = start_java_process(jar2_path, port2)

    try:
        if not wait_until_up(engine1_url, startup_deadline_s):
            raise RuntimeError(f"Engine 1 did not come up at {engine1_url} within {startup_deadline_s}s")
        if not wait_until_up(engine2_url, startup_deadline_s):
            raise RuntimeError(f"Engine 2 did not come up at {engine2_url} within {startup_deadline_s}s")
        print("Both servers are running. Ready to play.")

        engine1_wins = engine2_wins = draws = 0

        for game_number in range(1, games + 1):
            print(f"\n=== Starting game {game_number} ===")

            if not (reset_board(engine1_url) and reset_board(engine2_url)):
                print(f"Failed to reset one of the boards for game {game_number}; aborting.")
                break

            time.sleep(0.2)

            last1 = get_last_move(engine1_url)
            last2 = get_last_move(engine2_url)

            # NOTE: Python passes SECONDS; controller converts to ms now.
            if not (set_time_limit(engine1_url, engine_time_limit) and set_time_limit(engine2_url, engine_time_limit)):
                print(f"Failed to set time limits for game {game_number}; aborting.")
                break

            start_autoplay(engine1_url, "WHITE")
            start_autoplay(engine2_url, "BLACK")

            max_ply = 500
            ply = 0

            try:
                while True:
                    ply += 1
                    if ply > max_ply:
                        draws += 1
                        print("Max ply reached; declaring draw.")
                        break

                    # --- WHITE move from Engine1; mirror to Engine2 ---
                    deadline = time.time() + move_timeout_s
                    moved = False
                    while time.time() < deadline:
                        lm1 = get_last_move(engine1_url)  # might be None (204) or a dict
                        if lm1 and lm1 != last1:
                            last1 = lm1
                            if ('from' in lm1 and 'to' in lm1
                                    and lm1['from'] and lm1['to']):
                                try:
                                    make_move(engine2_url, lm1['from'], lm1['to'])
                                    last2 = {'from': lm1['from'], 'to': lm1['to'],
                                             'currentState': lm1.get('currentState')}
                                except requests.HTTPError as e:
                                    print(
                                        f"{engine2_name} rejected move {lm1['from']}-{lm1['to']}. "
                                        f"{format_http_error(e)}. Receiver fault -> {engine2_name} loses this game."
                                    )
                                    engine1_wins += 1
                                    moved = True
                                    break
                            moved = True
                            break
                        time.sleep(poll_sleep_s)
                    if not moved:
                        print(f"{engine1_name} failed to produce a move in time.")
                        # NEW: dump board + PV so you can judge
                        print_timeout_diagnostics(engine2_url, engine2_name)
                        print_timeout_diagnostics(engine1_url, engine1_name)  # optional: see the other side too
                        winner = manual_award_on_timeout(engine1_name, engine2_name)
                        if winner == 1:
                            engine1_wins += 1
                            print(f"Awarded win to {engine1_name} by manual decision.")
                        else:
                            engine2_wins += 1
                            print(f"Awarded win to {engine2_name} by manual decision.")
                        break

                    game_state = None
                    if last1 and 'currentState' in last1:
                        game_state = check_game_state(last1['currentState'])
                    if game_state:
                        if game_state == "white":
                            engine1_wins += 1
                        elif game_state == "black":
                            engine2_wins += 1
                        elif game_state == "draw":
                            draws += 1
                        break

                    # --- BLACK move from Engine2; mirror to Engine1 ---
                    deadline = time.time() + move_timeout_s
                    moved = False
                    while time.time() < deadline:
                        lm2 = get_last_move(engine2_url)
                        if lm2 and lm2 != last2:
                            last2 = lm2
                            if ('from' in lm2 and 'to' in lm2
                                    and lm2['from'] and lm2['to']):
                                try:
                                    make_move(engine1_url, lm2['from'], lm2['to'])
                                    last1 = {'from': lm2['from'], 'to': lm2['to'],
                                             'currentState': lm2.get('currentState')}
                                except requests.HTTPError as e:
                                    print(
                                        f"{engine1_name} rejected move {lm2['from']}-{lm2['to']}. "
                                        f"{format_http_error(e)}. Receiver fault -> {engine1_name} loses this game."
                                    )
                                    engine2_wins += 1
                                    moved = True
                                    break
                            moved = True
                            break
                        time.sleep(poll_sleep_s)
                    if not moved:
                        print(f"{engine2_name} failed to produce a move in time.")
                        # NEW: dump board + PV so you can judge
                        print_timeout_diagnostics(engine2_url, engine2_name)
                        print_timeout_diagnostics(engine1_url, engine1_name)  # optional: see the other side too
                        winner = manual_award_on_timeout(engine1_name, engine2_name)
                        if winner == 1:
                            engine1_wins += 1
                            print(f"Awarded win to {engine1_name} by manual decision.")
                        else:
                            engine2_wins += 1
                            print(f"Awarded win to {engine2_name} by manual decision.")
                        break

                    game_state = None
                    if last2 and 'currentState' in last2:
                        game_state = check_game_state(last2['currentState'])
                    if not game_state and last1 and 'currentState' in last1:
                        game_state = check_game_state(last1['currentState'])
                    if game_state:
                        if game_state == "white":
                            engine1_wins += 1
                        elif game_state == "black":
                            engine2_wins += 1
                        elif game_state == "draw":
                            draws += 1
                        break

                print(f"{engine1_name} wins: {engine1_wins}, "
                      f"{engine2_name} wins: {engine2_wins}, "
                      f"Draws: {draws}")

            except KeyboardInterrupt:
                print("Game interrupted by user.")
                break

    finally:
        for p in (e1, e2):
            try:
                if p and p.poll() is None:
                    p.terminate()
                    try:
                        p.wait(timeout=5)
                    except Exception:
                        p.kill()
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
                        help="Time limit configured on the engine (MILLISECONDS per move/search window).")
    parser.add_argument("--move-timeout", type=float, default=6.0,
                        help="Wall-clock tolerance (seconds) before pausing for manual decision.")
    parser.add_argument("--startup-deadline", type=float, default=60.0,
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
        engine_time_limit=args.engine_time_limit,   # <-- milliseconds, passed through unchanged
        move_timeout_s=args.move_timeout,          # <-- seconds
        startup_deadline_s=args.startup_deadline
    )


if __name__ == "__main__":
    main()
