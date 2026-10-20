#!/usr/bin/env python3
"""Continuous, SPRT-gated network generations on this PC: self-play never stops, the GPU trains, cutechess judges.

Loop:
  1. DataGen (packed .bin) plays with the current best network on --threads cores, all the time. With the best
     network's uncertainty head (--unc-file) it searches positions the network is unsure about --unc-nodes times
     deeper and starts --unc-fork of its games from such positions; rented servers (--cloud) add their self-play.
  2. Once the running data file has --positions new positions since the last candidate, a mix is built (the newest
     --window self-play files incl. the running one, the elite games, endgames twice; shuffled, scripts/nnue/mix.py)
     and a candidate is trained on the GPU (scripts/nnue/train_gpu.py) while self-play continues.
  3. parity.py checks the exported file against the Java engine; then the candidate plays an SPRT [0, 5] against the
     best (10+0.1, --concurrency cores next to self-play).
  4. H1: the candidate becomes the best, its uncertainty head is trained (scripts/nnue/uncertainty_probe.py), self-play
     here and on the servers restarts with both (into new files). H0: more data, next round.
Releases to the server stay manual (the best network is E:/Chess-Data/nets/best.bin; history in --state).

State is saved after every step; restarting the script resumes (it adopts nothing it did not start itself).

Usage (PyTorch venv for the GPU steps, Python with numpy for the rest; run from the repository):
  E:/Tools/torch-venv/Scripts/python.exe scripts/tuning/gpu_generations.py --threads 20 --concurrency 4
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CORE_SOURCES = ["src/main/java/julius/game/chessengine/core", "src/main/java/julius/game/chessengine/syzygy/bridge"]
PY = sys.executable
BURST = REPO / "scripts/cloud/burst.py"
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


def focus_options(args) -> list:
    """DataGen's uncertainty focus (deeper searches and extra games where the best network is unsure), if the best
    network has its uncertainty head (written on every promotion by train_head)."""
    if not Path(args.unc_file).exists() or (args.unc_nodes <= 1 and args.unc_fork <= 0):
        return []
    return [f"-Dcore.uncFile={args.unc_file}", f"-Dgen.uncNodes={args.unc_nodes}", f"-Dgen.uncFork={args.unc_fork}"]


def train_head(args, pt: Path, data: Path, log_path: Path) -> bool:
    """Trains the uncertainty head of the (new) best network on recent self-play and writes it to --unc-file."""
    with open(log_path, "w", encoding="utf-8") as f:
        r = subprocess.run([PY, str(REPO / "scripts/nnue/uncertainty_probe.py"), "--pt", str(pt), "--data", str(data),
                            "--net", args.best, "--export", args.unc_file], stdout=f, stderr=subprocess.STDOUT)
    if r.returncode != 0:
        Path(args.unc_file).unlink(missing_ok=True)  # no head is better than one of another network
    return r.returncode == 0


def start_datagen(args, classes: Path, out: Path) -> int:
    dll = Path(args.work) / "JSyzygy.dll"
    if not dll.exists():
        shutil.copy(REPO / "target/native/Release/JSyzygy.dll", dll)
    cmd = ["java", "-Xmx6g", "--add-modules", "jdk.incubator.vector", f"-Dgen.starts={args.starts}",
           "-Dgen.startFraction=0.4", f"-Dgen.syzygy={args.syzygy}", f"-Dchessengine.syzygy.nativeLibrary={dll}",
           "-Dgen.winScore=2000", "-Dgen.winPlies=8", "-Dgen.drawPly=160", "-Dgen.drawPlies=20",
           *focus_options(args),
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


REMOTE_ENGINE = ("#!/bin/sh\nexec /root/alieknek/jdk/bin/java -Xms128m -Xmx256m -XX:+UseParallelGC "
                 "--add-modules jdk.incubator.vector -Dcore.nnueFile=/root/sprt/{tag}.bin -cp /root/alieknek/classes "
                 "julius.game.chessengine.core.Uci\n")


def remote_sprt(args, name: str, candidate: Path, best: Path) -> str:
    """The same SPRT [0, 5] at 10+0.1 with fastchess on a burst.py server (--sprt-host): /root/fastchess/fastchess
    and the engine classes of burst.py setup, which must come from the same git ref as --engine-ref. Many games at
    once instead of --concurrency here; returns 'H1', 'H0' or the last result line."""
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", "-o", "ServerAliveInterval=30", "-o",
           "ServerAliveCountMax=6", "-i", args.ssh_key, f"root@{args.sprt_host}"]
    for tag, net in (("cand", candidate), ("best", best)):
        with open(net, "rb") as f:
            subprocess.run(ssh + [f"mkdir -p /root/sprt && cat > /root/sprt/{tag}.bin"], stdin=f, check=True,
                           timeout=600)
        subprocess.run(ssh + [f"cat > /root/sprt/{tag}.sh && chmod +x /root/sprt/{tag}.sh"],
                       input=REMOTE_ENGINE.format(tag=tag).encode(), check=True, timeout=120)
    cmd = ("/root/fastchess/fastchess -engine cmd=/root/sprt/cand.sh name=engine -engine cmd=/root/sprt/best.sh "
           "name=best -each proto=uci tc=10+0.1 option.Hash=32 option.Threads=1 timemargin=300 "
           "-openings file=/root/alieknek/openings.epd format=epd order=random -repeat -rounds 2000 -games 2 "
           f"-concurrency {args.sprt_concurrency} -sprt elo0=0 elo1=5 alpha=0.05 beta=0.05 "
           "-draw movenumber=40 movecount=8 score=8 -resign movecount=4 score=800 twosided=true -recover 2>&1 "
           "| grep -v -e '^Started game' -e '^Position;' -e '^Moves;' -e '^Warning;' -e '^Info;'")
    log_path = Path(r"E:\ChessEngines\harness\results") / f"{name}.log"
    with open(log_path, "w", encoding="utf-8") as f:
        subprocess.run(ssh + [cmd], stdout=f, stderr=subprocess.STDOUT, timeout=12 * 3600)
    text = log_path.read_text(encoding="utf-8", errors="replace")
    if "H1 was accepted" in text:
        return "H1"
    if "H0 was accepted" in text:
        return "H0"
    games = [line.strip() for line in text.splitlines() if line.startswith("Games:")]
    return games[-1] if games else "no result"


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
    ap.add_argument("--unc-file", default=r"E:\Chess-Data\nets\best-unc.bin",
                    help="uncertainty head of the best network (retrained on every promotion)")
    ap.add_argument("--unc-nodes", type=float, default=3.0, help="node multiplier for uncertain positions (1 = off)")
    ap.add_argument("--unc-fork", type=float, default=0.2, help="share of games started from uncertain positions")
    ap.add_argument("--sprt-host", help="run the SPRTs with fastchess on this burst.py server (many games at once)")
    ap.add_argument("--sprt-concurrency", type=int, default=30)
    ap.add_argument("--ssh-key", default=str(Path.home() / ".ssh" / "id_ed25519_hetzner"))
    ap.add_argument("--cloud", default=r"E:\Chess-Data\cloud",
                    help="self-play of rented servers (scripts/cloud/burst.py): synced every pass, counted and mixed")
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
    cloud = Path(args.cloud)
    cloud_base = state.setdefault("cloudBase", {})

    def judge(record: dict, gdir: Path, net: Path, cand_dir: Path) -> None:
        """SPRT of a trained candidate against the best and its promotion on H1. state['pending'] holds the
        candidate meanwhile, so a restarted loop tests it again instead of dropping it."""
        gen = record["generation"]
        state["pending"] = record
        save()
        log(f"generation {gen}: SPRT against the best" + (f" on {args.sprt_host}" if args.sprt_host else ""))
        name = f"gpugen-cand{record['attempt']:02d}"
        if args.sprt_host:
            verdict = remote_sprt(args, name, net, Path(args.best))
        else:
            verdict = sprt(name, cand_dir / "run.bat", best_dir / "run.bat", args.concurrency)
        record["verdict"] = verdict
        log(f"generation {gen}: {verdict}")
        if verdict == "H1":
            dg = state.get("datagen")
            current = Path(dg["file"]) if dg else None
            # burst.py names cloud files after the first 8 hex digits of their network's SHA-1
            previous = hashlib.sha1(Path(args.best).read_bytes()).hexdigest()[:8]
            shutil.copy(args.best, gdir / "previous_best.bin")
            shutil.copy(net, args.best)
            compile_engine(best_dir, Path(args.best))
            if dg:
                stop(dg["pid"])  # self-play continues with the new best (new file) on the next pass
            state["datagen"] = None
            # its uncertainty head, on the self-play of the previous best (the new one has none yet)
            own = [*([current] if current else []), *(Path(f) for f in cloud_base if f"-{previous}-" in Path(f).name)]
            ok = bool(own) and train_head(args, net.with_suffix(".pt"), max(own, key=positions), gdir / "unc.log")
            log(f"generation {gen}: uncertainty head {'trained' if ok else 'FAILED - focus off'}")
            if (cloud / "hosts.json").exists():
                subprocess.run([PY, str(BURST), "net", "all", "--net", args.best, "--unc", args.unc_file,
                                "--unc-nodes", str(args.unc_nodes), "--unc-fork", str(args.unc_fork),
                                "--dir", str(cloud)], capture_output=True, timeout=600)
            # the next candidate waits for 50M positions of the new best: what came in during the SPRT is old
            for f in cloud_base:
                cloud_base[f] = positions(Path(f))
            state["generation"] = gen
            record["promoted"] = True
        state.pop("pending", None)
        state["history"].append(record)
        save()

    def ensure_datagen() -> dict:
        """Self-play with the best network runs here (restarted into a new file if it is not)."""
        dg = state.get("datagen")
        if not dg or not alive(dg["pid"]):
            out = work / f"sp-gen{state['generation'] + 1:02d}-{time.strftime('%m%d%H%M')}.bin"
            classes = compile_engine(work / "datagen", Path(args.best))
            state["datagen"] = dg = {"pid": start_datagen(args, classes, out), "file": str(out), "base": 0}
            state["data"].append(str(out))
            save()
        return dg

    pending = state.get("pending")
    if pending:
        ensure_datagen()  # self-play goes on during the SPRT
        log(f"resuming the SPRT of {pending['net']}")
        gdir = Path(pending["net"]).parent
        judge(pending, gdir, Path(pending["net"]), gdir / "engine")
    while True:
        gen = state["generation"] + 1
        dg = ensure_datagen()
        current = Path(dg["file"])
        if (cloud / "hosts.json").exists():
            subprocess.run([PY, str(BURST), "sync", "--dir", str(cloud)], capture_output=True, timeout=3600)
            for f in sorted(cloud.glob("cloud-*.bin"), key=lambda p: p.stat().st_ctime):
                if str(f) not in state["data"]:
                    state["data"].append(str(f))
                    cloud_base[str(f)] = 0
                    log(f"cloud self-play: {f.name}")
                    save()
        new = positions(current) - dg.get("base", 0) + sum(positions(Path(f)) - b for f, b in cloud_base.items())
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
        for f in cloud_base:
            cloud_base[f] = positions(Path(f))
        if parity.returncode != 0:
            log(f"generation {gen}: parity failed - {record['parity']}")
            record["verdict"] = "parity failed"
            state["history"].append(record)
            save()
        else:
            judge(record, gdir, net, cand_dir)


if __name__ == "__main__":
    main()
