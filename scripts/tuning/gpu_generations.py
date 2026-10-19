#!/usr/bin/env python3
"""Continuous, SPRT-gated network generations on this PC: self-play never stops, the GPU trains, cutechess judges.

Loop:
  1. DataGen (packed .bin) plays with the current best network on --threads cores, all the time.
  2. Once the running data file has --positions new positions since the last candidate, a mix is built (the newest
     --window self-play files incl. the running one, the elite games, endgames twice; shuffled, scripts/nnue/mix.py)
     and a candidate is trained on the GPU (scripts/nnue/train_gpu.py) while self-play continues.
  3. parity.py checks the exported file against the Java engine; then the candidate plays an SPRT [0, 5] against the
     best (10+0.1, --concurrency cores next to self-play).
  4. H1: the candidate becomes the best, self-play restarts with it (into a new file). H0: more data, next round.
Releases to the server stay manual (the best network is E:/Chess-Data/nets/best.bin; history in --state).

State is saved after every step; restarting the script resumes (it adopts nothing it did not start itself).

Usage (PyTorch venv for the GPU steps, Python with numpy for the rest; run from the repository):
  E:/Tools/torch-venv/Scripts/python.exe scripts/tuning/gpu_generations.py --threads 20 --concurrency 4
"""
import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CORE_SOURCES = ["src/main/java/julius/game/chessengine/core", "src/main/java/julius/game/chessengine/syzygy/bridge"]
PY = sys.executable
RECORD = 32


def log(msg: str) -> None:
    print(f"[{time.strftime('%Y-%m-%d %H:%M')}] {msg}", flush=True)


# Engines are built from a fixed git ref checked out in a worktree, never from the live working tree: experiments
# lying there must not leak into the network SPRTs (set by main from --engine-ref).
SOURCE = REPO


def checkout(ref: str, work: Path) -> Path:
    """A detached worktree of ref under work/src (re-created on every start)."""
    src = work / "src"
    subprocess.run(["git", "-C", str(REPO), "worktree", "remove", "--force", str(src)], capture_output=True)
    subprocess.run(["git", "-C", str(REPO), "worktree", "prune"], capture_output=True)
    subprocess.run(["git", "-C", str(REPO), "worktree", "add", "--detach", str(src), ref], check=True,
                   capture_output=True)
    return src


def compile_engine(target: Path, net: Path) -> Path:
    """javac the core (with the Vector API module) into target/classes with net as core/nnue.bin, plus run.bat."""
    classes = target / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    (classes / "core").mkdir(parents=True)
    sources = [str(p) for d in CORE_SOURCES for p in (SOURCE / d).rglob("*.java")]
    subprocess.run(["javac", "-nowarn", "--add-modules", "jdk.incubator.vector", "-d", str(classes), *sources],
                   check=True, capture_output=True)
    shutil.copy(SOURCE / "src/main/resources/core/eval-weights.txt", classes / "core" / "eval-weights.txt")
    shutil.copy(net, classes / "core" / "nnue.bin")
    (target / "run.bat").write_text(
        "@echo off\r\njava -Xms256m -Xmx512m -XX:+UseParallelGC --add-modules jdk.incubator.vector "
        f"-cp \"%~dp0classes\" julius.game.chessengine.core.Uci\r\n", encoding="ascii")
    return classes


def positions(path: Path) -> int:
    return path.stat().st_size // RECORD if path.exists() else 0


def alive(pid: int) -> bool:
    # bytes: tasklist prints in the console code page (German umlauts broke text decoding)
    out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True).stdout
    return str(pid).encode() in out


def start_datagen(args, classes: Path, out: Path) -> int:
    dll = Path(args.work) / "JSyzygy.dll"
    if not dll.exists():
        shutil.copy(REPO / "target/native/Release/JSyzygy.dll", dll)
    cmd = ["java", "-Xmx6g", "--add-modules", "jdk.incubator.vector", f"-Dgen.starts={args.starts}",
           "-Dgen.startFraction=0.4", f"-Dgen.syzygy={args.syzygy}", f"-Dchessengine.syzygy.nativeLibrary={dll}",
           "-Dgen.winScore=2000", "-Dgen.winPlies=8", "-Dgen.drawPly=160", "-Dgen.drawPlies=20",
           "-cp", str(classes), "julius.game.chessengine.core.tools.DataGen", str(out), str(args.threads),
           "1000000000", str(args.nodes), args.openings]
    logf = open(out.with_suffix(".log"), "a", encoding="utf-8")
    proc = subprocess.Popen(cmd, stdout=logf, stderr=subprocess.STDOUT,
                            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.BELOW_NORMAL_PRIORITY_CLASS)
    log(f"self-play started: {out.name} (pid {proc.pid}, {args.threads} threads)")
    return proc.pid


def stop(pid: int) -> None:
    subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)


def sprt(name: str, a: Path, b: Path, conc: int) -> str:
    """cutechess SPRT [0, 5] at 10+0.1 between two run.bat engines; returns 'H1', 'H0' or the last score line."""
    log_path = Path(r"E:\ChessEngines\harness\results") / f"{name}.log"
    cmd = [r"C:\Program Files (x86)\Cute Chess\cutechess-cli.exe",
           "-engine", f"name={a.parent.name}", f"cmd={a}", "-engine", f"name={b.parent.name}", f"cmd={b}",
           "-each", "proto=uci", "tc=10+0.1", "option.Hash=32", "option.Threads=1", "timemargin=300",
           "-openings", r"file=E:\ChessEngines\harness\elite_openings.epd", "format=epd", "order=random",
           "-repeat", "-games", "2", "-rounds", "2000", "-concurrency", str(conc),
           "-sprt", "elo0=0", "elo1=5", "alpha=0.05", "beta=0.05",
           "-draw", "movenumber=40", "movecount=8", "score=8", "-resign", "movecount=4", "score=800", "twosided=true",
           "-recover", "-ratinginterval", "50"]
    with open(log_path, "w", encoding="utf-8") as f:
        subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT)
    text = log_path.read_text(encoding="utf-8", errors="replace")
    if "H1 was accepted" in text:
        return "H1"
    if "H0 was accepted" in text:
        return "H0"
    scores = [l for l in text.splitlines() if l.startswith("Score of")]
    return scores[-1] if scores else "no result"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default=r"E:\Chess-Data\gpugen")
    ap.add_argument("--state", default=r"E:\Chess-Data\gpugen\state.json")
    ap.add_argument("--best", default=r"E:\Chess-Data\nets\best.bin")
    ap.add_argument("--seed-data", nargs="*", default=[r"E:\Chess-Data\packed\gen02.bin",
                                                       r"E:\Chess-Data\bigdata\sp-g2-a.bin",
                                                       r"E:\Chess-Data\bigdata\sp-g3-a.bin"],
                    help="self-play files the window starts with (oldest first)")
    # Positions from Lichess elite games scored by our own search (core.tools.Relabel)
    ap.add_argument("--elite", default=r"E:\Chess-Data\packed\elite2M-own.bin")
    ap.add_argument("--window", type=int, default=4, help="newest self-play files per mix")
    ap.add_argument("--positions", type=int, default=50_000_000, help="new positions per candidate")
    ap.add_argument("--layout", default="plain", choices=["plain", "kb8"])
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--threads", type=int, default=20)
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--nodes", type=int, default=6000)
    ap.add_argument("--starts", default=r"E:\Chess-Data\selfplay\starts_lichess_mix.txt")
    ap.add_argument("--syzygy", default=r"C:\Syzygy")
    ap.add_argument("--openings", default=r"E:\ChessEngines\harness\elite_openings.epd")
    ap.add_argument("--valid-data", default=r"E:\Chess-Data\packed\val_f256.bin")
    ap.add_argument("--engine-ref", default="v4.1.0", help="git ref the engines are compiled from")
    args = ap.parse_args()

    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    global SOURCE
    SOURCE = checkout(args.engine_ref, work)
    log(f"engines from {args.engine_ref} ({SOURCE})")
    state_path = Path(args.state)
    state = json.loads(state_path.read_text()) if state_path.exists() else {
        "generation": 0, "data": list(args.seed_data), "datagen": None, "trainedAt": 0, "history": []}

    def save():
        state_path.write_text(json.dumps(state, indent=1), encoding="utf-8")

    best_dir = work / "best"
    compile_engine(best_dir, Path(args.best))
    while True:
        gen = state["generation"] + 1
        dg = state.get("datagen")
        if not dg or not alive(dg["pid"]):
            out = work / f"sp-gen{gen:02d}-{time.strftime('%m%d%H%M')}.bin"
            classes = compile_engine(work / "datagen", Path(args.best))
            state["datagen"] = dg = {"pid": start_datagen(args, classes, out), "file": str(out), "base": 0}
            state["data"].append(str(out))
            save()
        current = Path(dg["file"])
        new = positions(current) - dg.get("base", 0)
        if new < args.positions:
            time.sleep(600)
            continue
        # ---- candidate (every attempt gets its own folder; a promoted one becomes generation `gen`)
        attempt = len(state["history"]) + 1
        gdir = work / f"cand{attempt:02d}"
        gdir.mkdir(exist_ok=True)
        window = state["data"][-args.window:]
        mix = gdir / "mix.bin"
        log(f"generation {gen}: mixing {', '.join(Path(w).name for w in window)}")
        subprocess.run([PY, str(REPO / "scripts/nnue/mix.py"), "--out", str(mix), args.elite,
                        *["+" + w for w in window]], check=True)
        net = gdir / "candidate.bin"
        log(f"generation {gen}: training {args.layout} on the GPU")
        with open(gdir / "train.log", "w", encoding="utf-8") as f:
            subprocess.run([PY, str(REPO / "scripts/nnue/train_gpu.py"), "--layout", args.layout, "--data", str(mix),
                            "--valid-data", args.valid_data, "--out", str(net), "--epochs", str(args.epochs)],
                           stdout=f, stderr=subprocess.STDOUT, check=True)
        mix.unlink()  # several GB; the sources stay
        cand_dir = gdir / "engine"
        compile_engine(cand_dir, net)
        parity = subprocess.run([PY, str(REPO / "scripts/nnue/parity.py"), "--net", str(net), "--data",
                                 args.valid_data, "--classes", str(cand_dir / "classes"), "--count", "400"],
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
        record = {"attempt": attempt, "generation": gen, "window": window, "net": str(net), "positions": new,
                  "parity": parity.stdout.strip().splitlines()[-1] if parity.stdout else parity.stderr[-300:]}
        dg["base"] = positions(current)
        if parity.returncode != 0:
            log(f"generation {gen}: parity failed - {record['parity']}")
            record["verdict"] = "parity failed"
        else:
            log(f"generation {gen}: SPRT against the best")
            verdict = sprt(f"gpugen-cand{attempt:02d}", cand_dir / "run.bat", best_dir / "run.bat", args.concurrency)
            record["verdict"] = verdict
            log(f"generation {gen}: {verdict}")
            if verdict == "H1":
                shutil.copy(args.best, gdir / "previous_best.bin")
                shutil.copy(net, args.best)
                compile_engine(best_dir, Path(args.best))
                stop(dg["pid"])  # self-play continues with the new best (new file) on the next pass
                state["datagen"] = None
                state["generation"] = gen
                record["promoted"] = True
        state["history"].append(record)
        save()


if __name__ == "__main__":
    main()
