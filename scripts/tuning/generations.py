#!/usr/bin/env python3
"""Evolution of the NNUE network through generations, gated by SPRT.

Each generation:
  1. the current best engine plays self-play games (core.tools.DataGen) - half of them from late-middlegame /
     endgame start positions, finished games scored with the 6-piece Syzygy tables - so every generation's
     training data comes from a stronger player than the one before;
  2. a child network is trained (core.nnue.Trainer) on a window of the most recent data sets plus the elite
     games, with endgame positions (<= 10 pieces) counted twice to match the real games' mix;
  3. the child plays the parent in an SPRT (colour-swapped pairs, balanced openings). Only an accepted child
     (H1) becomes the new best; a rejected one is kept on disk for reference.

This replaces the old evolve.py/evolve_nnue.py loops, which picked the best of four random mutants after
40 games each - with ~+-100 Elo noise per match, that mostly selected luck. State is saved after every
stage (--state), so a run can be stopped and resumed.

Usage:
  python scripts/tuning/generations.py --gens 2 --positions 15000000
"""
import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cutechess import Engine, run_match  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
CORE_SOURCES = ["src/main/java/julius/game/chessengine/core", "src/main/java/julius/game/chessengine/syzygy/bridge"]


def log(msg: str) -> None:
    print(f"[{time.strftime('%Y-%m-%d %H:%M')}] {msg}", flush=True)


def compile_core(out: Path) -> Path:
    """javac the core (no dependencies) into out/classes and copy the eval weights."""
    classes = out / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    (classes / "core").mkdir(parents=True)
    sources = [str(p) for d in CORE_SOURCES for p in (REPO / d).rglob("*.java")]
    subprocess.run(["javac", "-nowarn", "--add-modules", "jdk.incubator.vector", "-d", str(classes), *sources], check=True)
    shutil.copy(REPO / "src/main/resources/core/eval-weights.txt", classes / "core" / "eval-weights.txt")
    return classes


def engine_dir(base: Path, root: Path, name: str, net: Path) -> Path:
    """A copy of the compiled classes with `net` as core/nnue.bin."""
    target = root / name / "classes"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(base, target)
    shutil.copy(net, target / "core" / "nnue.bin")
    return target


def trim_last_line(path: Path) -> int:
    """Drops a possibly half-written last line (DataGen is stopped by count, not mid-write, but be safe)."""
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    if lines and not lines[-1].endswith("\n"):
        lines.pop()
    path.write_text("".join(lines), encoding="utf-8")
    return len(lines)


def endgame_extra(sources: list, out: Path, max_pieces: int = 10) -> int:
    n = 0
    with out.open("w", encoding="utf-8") as w:
        for src in sources:
            with open(src, encoding="utf-8") as r:
                for line in r:
                    board = line.split(" ", 1)[0]
                    if sum(ch.isalpha() for ch in board) <= max_pieces:
                        w.write(line)
                        n += 1
    return n


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--best", default=r"E:\Chess-Data\nets\best.bin", help="current best network")
    ap.add_argument("--work", default=r"E:\Chess-Data\generations", help="engines, data and nets per generation")
    ap.add_argument("--state", default=r"E:\Chess-Data\generations\state.json")
    ap.add_argument("--gens", type=int, default=1)
    ap.add_argument("--positions", type=int, default=15_000_000, help="new self-play positions per generation")
    ap.add_argument("--threads", type=int, default=20)
    ap.add_argument("--nodes", type=int, default=6000)
    ap.add_argument("--starts", default=r"E:\Chess-Data\selfplay\starts_8_20.txt")
    ap.add_argument("--syzygy", default=r"C:\Syzygy")
    ap.add_argument("--native", default=str(REPO / r"target\native\Release\JSyzygy.dll"))
    ap.add_argument("--elite", default=r"E:\Chess-Data\tuning\elite2M-own.txt")
    ap.add_argument("--window", nargs="*", default=[r"E:\Chess-Data\selfplay\v5b_nnue.txt", r"E:\Chess-Data\selfplay\v5c_endgame.txt"],
                    help="earlier self-play sets kept in the training window")
    ap.add_argument("--window-size", type=int, default=3, help="how many self-play sets to train on")
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--buckets", type=int, default=8)
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--tc", default="10+0.1")
    ap.add_argument("--concurrency", type=int, default=10)
    args = ap.parse_args()

    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    state_path = Path(args.state)
    state = json.loads(state_path.read_text()) if state_path.exists() else {
        "generation": 0, "window": list(args.window), "history": []}
    best = Path(args.best)
    if not best.exists():
        raise SystemExit(f"best network not found: {best}")

    base = compile_core(work / "build")
    for _ in range(args.gens):
        gen = state["generation"] + 1
        gdir = work / f"gen{gen:02d}"
        gdir.mkdir(exist_ok=True)
        data = gdir / "selfplay.txt"
        log(f"generation {gen}: self-play with {best.name}")
        parent = engine_dir(base, gdir, "parent", best)
        if not data.exists() or sum(1 for _ in open(data, encoding="utf-8")) < args.positions:
            subprocess.run([
                "java", "-Xmx10g", "--add-modules", "jdk.incubator.vector",
                f"-Dgen.starts={args.starts}", "-Dgen.startFraction=0.5",
                f"-Dgen.syzygy={args.syzygy}", f"-Dchessengine.syzygy.nativeLibrary={args.native}",
                "-Dgen.winScore=2000", "-Dgen.winPlies=8", "-Dgen.drawPly=160", "-Dgen.drawPlies=20",
                "-cp", str(parent), "julius.game.chessengine.core.tools.DataGen", str(data),
                str(args.threads), str(args.positions), str(args.nodes), r"E:\ChessEngines\harness\elite_openings.epd",
            ], check=True)
        log(f"generation {gen}: {trim_last_line(data)} positions")

        window = (state["window"] + [str(data)])[-args.window_size:]
        extra = gdir / "endgame_extra.txt"
        log(f"generation {gen}: {endgame_extra(window, extra)} endgame positions counted twice")
        child_net = gdir / "child.bin"
        subprocess.run([
            "java", "-Xmx44g", "--add-modules", "jdk.incubator.vector", f"-Dtrain.buckets={args.buckets}",
            "-cp", str(base),
            "julius.game.chessengine.core.nnue.Trainer", str(child_net), str(args.hidden), str(args.epochs),
            "0.3", "230", args.elite, *window, str(extra),
        ], check=True)

        child = engine_dir(base, gdir, "child", child_net)
        result = run_match(Engine("child", str(child)), Engine("parent", str(parent)), args.tc, 1500,
                           args.concurrency, sprt=(0, 5), pgn=gdir / "sprt.pgn")
        log(f"generation {gen}: {result.summary()} -> {result.verdict}")
        record = {"generation": gen, "w": result.wins, "l": result.losses, "d": result.draws,
                  "elo": round(result.elo_diff, 1), "verdict": result.verdict, "data": str(data)}
        if result.verdict == "H1":
            shutil.copy(best, gdir / "previous_best.bin")
            shutil.copy(child_net, best)
            record["promoted"] = True
            log(f"generation {gen}: child promoted to {best}")
        state["generation"] = gen
        state["window"] = window
        state["history"].append(record)
        state_path.write_text(json.dumps(state, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
