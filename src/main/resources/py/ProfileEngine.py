#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import datetime as dt
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional, List, Pattern, Match, TextIO

DEFAULT_SYZYGY_NATIVE = r"C:\\Development\\Chess-Engine\\target\\classes\\natives\\win-x86_64\\Release\\JSyzygy.dll"
DEFAULT_SYZYGY_PATHS = r"C:\\Syzygy"


def resolve_syzygy_from_env():
    native = os.getenv("CHESSENGINE_SYZYGY_NATIVE") or DEFAULT_SYZYGY_NATIVE
    paths = (
        os.getenv("CHESSENGINE_SYZYGY_PATHS")
        or os.getenv("CHESSENGINE_SYZYGY_PATH")
        or DEFAULT_SYZYGY_PATHS
    )
    return native, paths

# ---------------- Utilities ----------------

def ts() -> str:
    return dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]

def log_line(writer: TextIO, chan: str, msg: str, echo: bool = True) -> None:
    if chan is None:
        chan = ""
    if msg is None:
        msg = ""
    line = f"{ts()} [{chan}] {msg}"
    writer.write(line + "\n")
    writer.flush()
    if echo:
        if chan == "stderr":
            print(msg, file=sys.stderr, flush=True)
        else:
            print(msg, flush=True)

def ensure_dir(p: Path) -> None:
    p.mkdir(parents=True, exist_ok=True)

def latest_uci_jar(target_dir: Path) -> Optional[Path]:
    """Find chess-engine-*-uci.jar with highest (version, mtime)."""
    if not target_dir.exists():
        return None
    candidates = []
    rx = re.compile(r"chess-engine-(\d+(?:\.\d+)*)-uci\.jar$")
    for f in target_dir.glob("chess-engine-*-uci.jar"):
        m = rx.search(f.name)
        if m:
            ver_tuple = tuple(int(x) for x in m.group(1).split("."))
        else:
            ver_tuple = (0, 0, 0, 0)
        candidates.append((ver_tuple, f.stat().st_mtime, f))
    if not candidates:
        return None
    candidates.sort()
    return candidates[-1][2]

# ---------------- Async readers ----------------

class StreamReader(threading.Thread):
    """
    Reads a binary stream line-by-line. If out_q is provided (stdout reader),
    enqueues lines for pattern waits; else logs only (stderr reader).
    """
    def __init__(self, name: str, stream, out_q, writer: TextIO, echo: bool = True):
        super(StreamReader, self).__init__(daemon=True, name=name)
        self.stream = stream
        self.out_q = out_q  # type: Optional[queue.Queue]
        self.writer = writer
        self.echo = echo

    def run(self) -> None:
        try:
            for raw in iter(self.stream.readline, b""):
                try:
                    line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                except Exception:
                    line = raw.decode(errors="replace").rstrip("\r\n")
                if self.out_q is not None:  # stdout path
                    log_line(self.writer, "stdout", line, echo=self.echo)
                    self.out_q.put(line)
                else:  # stderr path
                    log_line(self.writer, "stderr", line, echo=self.echo)
        except Exception as e:
            log_line(self.writer, "reader", f"{self.name} stopped: {e}", echo=self.echo)

# ---------------- UCI / Wait helpers ----------------

def wait_for_regex(out_q: queue.Queue, pattern: Pattern, context: str,
                   timeout_ms: int, writer: TextIO, echo: bool = True) -> Optional[Match]:
    """Drain queue until regex matches or timeout. Returns Match or None."""
    deadline = time.monotonic() + (max(timeout_ms, 1) / 1000.0)
    next_heartbeat = 0.0
    while True:
        remain = deadline - time.monotonic()
        if remain <= 0:
            return None
        try:
            line = out_q.get(timeout=min(remain, 0.25))
        except queue.Empty:
            if time.monotonic() >= next_heartbeat:
                log_line(writer, "wait",
                         "waiting for '{}' (ctx={}, t_left={}ms)".format(
                             pattern.pattern, context, int(max(0, (deadline - time.monotonic()) * 1000))),
                         echo=echo)
                next_heartbeat = time.monotonic() + 0.5
            continue

        m = pattern.search(line)
        if m:
            return m

def send(proc: subprocess.Popen, cmd: str, writer: TextIO, echo: bool = True) -> None:
    log_line(writer, "stdin", cmd, echo=echo)
    try:
        proc.stdin.write((cmd + "\n").encode("utf-8"))
        proc.stdin.flush()
    except Exception as e:
        log_line(writer, "error", f"failed to write stdin: {e}", echo=True)
        raise

# ---------------- Main ----------------

def detect_project_root(script_path: Path) -> Path:
    """
    Heuristic:
      1) CHESS_PROJECT_ROOT env
      2) climb upwards until a 'target' folder is found
      3) fallback to script_path.parents[3] (…/src/main/resources/py -> repo root)
      4) final fallback: cwd
    """
    env_root = os.getenv("CHESS_PROJECT_ROOT")
    if env_root:
        pr = Path(env_root)
        if (pr / "target").exists():
            return pr

    p = script_path.resolve()
    for ancestor in [p.parent] + list(p.parents):
        if (ancestor / "target").exists():
            return ancestor

    # If the script lives in .../src/main/resources/py, parents[3] should be repo root
    try:
        guess = script_path.resolve().parents[3]
        return guess
    except Exception:
        return Path.cwd()

def build_java_cmd(jar: Path, jfr_file: Path, jfr_duration: str,
                   chess_threads: str, lazy_threads: str, root_par_limit: str) -> List[str]:
    # JVM/env defaults (tuned for lower pauses & less thread thrash)
    syzygy_native, syzygy_paths = resolve_syzygy_from_env()
    java_xms = os.getenv("JAVA_XMS", "8g")
    java_xmx = os.getenv("JAVA_XMX", "8g")
    java_gc  = os.getenv("JAVA_GC",  "zgc")   # default: low-pause ZGC
    apc      = os.getenv("JAVA_ACTIVE_PROCESSORS", "12")  # cap workers below physical cores
    extra    = os.getenv(
        "JAVA_EXTRA_OPTS",
        "-XX:+AlwaysPreTouch "
        "-XX:+UnlockExperimentalVMOptions "
        "-XX:+UseLargePages "
        "-Dchessengine.tt.mb=512 "
        "-Dchessengine.uci.info.minIntervalMs=250 "
        "-Dchessengine.uci.info.maxPvLen=10 "
        "-Dchessengine.openingbook.enabled=false "
    )

    def gc_flag(v: str) -> str:
        v = v.lower().strip()
        if v == "g1":          return "-XX:+UseG1GC"
        if v == "shenandoah":  return "-XX:+UseShenandoahGC"
        if v == "zgc":         return "-XX:+UseZGC"
        return v

    cmd = [
        "java",
        "-Xms{}".format(java_xms),
        "-Xmx{}".format(java_xmx),
        gc_flag(java_gc),
        "-XX:ActiveProcessorCount={}".format(apc),
    ]

    extra_flags = [x for x in extra.split() if x.strip()]
    if syzygy_native:
        extra_flags.append(f"-Dchessengine.syzygy.nativeLibrary={syzygy_native}")
    if syzygy_paths:
        extra_flags.append(f"-Dchessengine.syzygy.paths={syzygy_paths}")
    if extra_flags:
        cmd += extra_flags

    cmd += [
        "-Dchessengine.searchThreads={}".format(chess_threads),
        "-Dchessengine.lazySmpThreads={}".format(lazy_threads),
        "-Dchessengine.rootParallelLimit={}".format(root_par_limit),
        "-Dlogging.level.root=INFO",
        " -XX:StartFlightRecording=name=uci,settings=profile,duration={},filename={}".format(
            jfr_duration, str(jfr_file)
        ).strip(),
        "-jar", str(jar),
    ]
    return cmd

def main() -> int:
    ap = argparse.ArgumentParser(description="Profile UCI engine with JFR and self-play.")
    ap.add_argument("--jar", dest="jar_path", help="Path to chess-engine-*-uci.jar")
    ap.add_argument("--profile-dir", dest="profile_dir", help="Directory for logs/JFR/summary")
    ap.add_argument("--movetime", dest="move_time_ms", type=int, default=2000)
    ap.add_argument("--ply", dest="ply_count", type=int, default=80)
    ap.add_argument("--jfr-duration", dest="jfr_duration", default="180s")
    ap.add_argument("--echo", action="store_true", help="Echo engine stdout/stderr to console")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    # Locate project root and default paths
    script_path = Path(__file__)
    project_root = detect_project_root(script_path)
    target_dir   = project_root / "target"
    profiles_dir = project_root / "profiles"

    # Resolve jar, profile dir
    if not args.jar_path:
        jar = latest_uci_jar(target_dir)
        if not jar:
            print("No chess-engine-*-uci.jar found in '{}'.".format(target_dir), file=sys.stderr)
            return 2
    else:
        jar = Path(args.jar_path)

    profiles = Path(args.profile_dir) if args.profile_dir else profiles_dir

    if not jar.exists():
        print("The UCI engine jar '{}' was not found.".format(jar), file=sys.stderr)
        return 2

    ensure_dir(profiles)

    if args.move_time_ms <= 0 or args.ply_count <= 0:
        print("MoveTimeMs and PlyCount must be > 0", file=sys.stderr)
        return 2

    # Files
    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    jfr_file = profiles / "uci-{}.jfr".format(timestamp)
    log_file = profiles / "uci-{}.log".format(timestamp)
    summary_file = profiles / "uci-{}-summary.json".format(timestamp)

    with log_file.open("w", encoding="utf-8") as writer:
        # Engine params (env overrides supported)
        chess_threads = os.getenv("CHESSENGINE_THREADS", "8")
        lazy_threads  = os.getenv("CHESSENGINE_LAZY_THREADS", "2")
        root_par_lim  = os.getenv("CHESSENGINE_ROOT_PAR_LIMIT", "12")

        cmd = build_java_cmd(jar, jfr_file, args.jfr_duration, chess_threads, lazy_threads, root_par_lim)

        log_line(writer, "phase", "====== START ======")
        log_line(writer, "info",  "Project root  : {}".format(project_root))
        log_line(writer, "info",  "Engine jar    : {}".format(jar))
        log_line(writer, "info",  "JFR duration  : {}".format(args.jfr_duration))
        log_line(writer, "info",  "Move time (ms): {}".format(args.move_time_ms))
        log_line(writer, "info",  "Ply count     : {}".format(args.ply_count))
        log_line(writer, "info",  "Profile dir   : {}".format(profiles))
        log_line(writer, "info",  "Log file      : {}".format(log_file))
        log_line(writer, "info",  "Search threads: {}".format(chess_threads))
        log_line(writer, "info",  "Lazy threads  : {}".format(lazy_threads))
        log_line(writer, "info",  "Root fanout   : {}".format(root_par_lim))
        log_line(writer, "info",  "JAVA CMD     : " + " ".join(cmd))

        if args.dry_run:
            log_line(writer, "phase", "====== DRY RUN END ======")
            return 0

        # Spawn process
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=str(jar.parent),  # run from jar directory
                shell=False
            )
        except FileNotFoundError:
            log_line(writer, "error", "java not found on PATH", echo=True)
            return 2

        log_line(writer, "info", "PID: {}".format(proc.pid))

        # Readers
        out_q = queue.Queue()  # type: queue.Queue
        t_out = StreamReader("stdout-reader", proc.stdout, out_q, writer, echo=args.echo)
        t_err = StreamReader("stderr-reader", proc.stderr, None,  writer, echo=args.echo)
        t_out.start(); t_err.start()

        # Early exit probe
        time.sleep(0.2)
        if proc.poll() is not None:
            log_line(writer, "error", "Java exited immediately. ExitCode={}".format(proc.returncode), echo=True)
            t_out.join(timeout=1.0); t_err.join(timeout=1.0)
            return 1

        # ---- Handshake
        log_line(writer, "phase", "====== HANDSHAKE ======")
        send(proc, "uci", writer, echo=True)

        time.sleep(0.2)
        if proc.poll() is not None:
            log_line(writer, "error", "Java exited right after 'uci'. ExitCode={}".format(proc.returncode), echo=True)
            t_out.join(timeout=1.0); t_err.join(timeout=1.0)
            return 1

        rx_uciok = re.compile(r"^uciok$")
        m = wait_for_regex(out_q, rx_uciok, "uci handshake", 30000, writer, echo=args.echo)
        if not m:
            log_line(writer, "error", "Timeout waiting for uciok.", echo=True)
            try: proc.terminate()
            except Exception: pass
            t_out.join(timeout=1.0); t_err.join(timeout=1.0)
            return 1

        send(proc, "isready", writer, echo=True)
        rx_readyok = re.compile(r"^readyok$")
        if not wait_for_regex(out_q, rx_readyok, "engine readiness", 30000, writer, echo=args.echo):
            log_line(writer, "error", "Timeout waiting for readyok.", echo=True)
            try: proc.terminate()
            except Exception: pass
            t_out.join(timeout=1.0); t_err.join(timeout=1.0)
            return 1

        send(proc, "ucinewgame", writer, echo=True)
        send(proc, "isready", writer, echo=True)
        if not wait_for_regex(out_q, rx_readyok, "new game readiness", 30000, writer, echo=args.echo):
            log_line(writer, "error", "Timeout waiting for readyok after ucinewgame.", echo=True)
            try: proc.terminate()
            except Exception: pass
            t_out.join(timeout=1.0); t_err.join(timeout=1.0)
            return 1

        # ---- Self-play
        log_line(writer, "phase", "====== SELF-PLAY ======")
        moves = []  # type: List[str]
        rx_bestmove = re.compile(r"^bestmove\s+(\S+)")

        for ply in range(1, args.ply_count + 1):
            pos = "position startpos"
            if moves:
                pos += " moves " + " ".join(moves)
            send(proc, pos, writer, echo=True)
            send(proc, "go movetime {}".format(args.move_time_ms), writer, echo=True)

            m = wait_for_regex(out_q, rx_bestmove, "bestmove ply {}".format(ply),
                               max(args.move_time_ms * 5, 5000), writer, echo=args.echo)
            if not m:
                send(proc, "stop", writer, echo=True)
                m = wait_for_regex(out_q, rx_bestmove, "bestmove (post-stop) ply {}".format(ply),
                                   3000, writer, echo=args.echo)

            if not m:
                log_line(writer, "warn", "No bestmove on ply {}; aborting.".format(ply), echo=True)
                break

            best = m.group(1)
            if not best.strip() or best.strip().lower() == "(none)":
                log_line(writer, "info", "No legal moves on ply {}.".format(ply), echo=True)
                break

            print("Ply {:2d}: {}".format(ply, best), flush=True)
            log_line(writer, "ply", "{}:{}".format(ply, best), echo=args.echo)
            moves.append(best)

            send(proc, "isready", writer, echo=True)
            if not wait_for_regex(out_q, rx_readyok, "post-move readiness ply {}".format(ply),
                                  30000, writer, echo=args.echo):
                log_line(writer, "error", "Timeout waiting for readyok after ply {}.".format(ply), echo=True)
                break

        # ---- Shutdown
        log_line(writer, "phase", "====== SHUTDOWN ======")
        try:
            send(proc, "quit", writer, echo=True)
            try:
                proc.stdin.close()
            except Exception:
                pass
        finally:
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                log_line(writer, "warn", "Engine did not exit in 10s after quit -> Kill()", echo=True)
                proc.kill()
                proc.wait(timeout=5)

        # Finalize
        t_out.join(timeout=1.0)
        t_err.join(timeout=1.0)

        exit_code = proc.returncode if proc.returncode is not None else -1

        summary = {
            "timestampUtc": dt.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S"),
            "engineJar": str(jar),
            "jfrDuration": args.jfr_duration,
            "moveTimeMs": args.move_time_ms,
            "requestedPlyCount": args.ply_count,
            "completedPlyCount": len(moves),
            "bestMoves": moves,
            "jfrFile": str(jfr_file),
            "logFile": str(log_file),
            "exitCode": exit_code,
        }
        with summary_file.open("w", encoding="utf-8") as sf:
            json.dump(summary, sf, indent=2)

        print("")
        print("Profiling run complete.")
        print("  Best moves played : {} plies".format(len(moves)))
        print("  Engine exit code  : {}".format(exit_code))
        print("  JFR capture       : {}".format(jfr_file))
        print("  Engine log        : {}".format(log_file))
        print("  Run summary       : {}".format(summary_file))
        print("")
        print("Open the JFR file in Java Mission Control to inspect hotspots.")

        return 0

if __name__ == "__main__":
    sys.exit(main())
