#!/usr/bin/env python3
"""Self-play on rented Linux servers (Hetzner auction / cloud, AWS, ...): a burst of training data in a few hours.

  burst.py setup <host> [--net FILE] [--ref REF]   copy the engine sources (git ref, default = the GPU loop's) and
                                                   the network, install Java 27, the 3-4-5 Syzygy tables and the JNI
                                                   library, start DataGen on all threads
  burst.py sync                                    append the new positions of every host to E:/Chess-Data/cloud/
  burst.py status                                  positions, rate and load per host
  burst.py net <host|all> --net FILE               restart self-play with another network (new output file)
  burst.py stop <host>                             final sync, stop self-play, forget the host
                                                   (cancel / delete the server yourself afterwards)

The server needs root ssh with --key (a fresh Debian/Ubuntu or the Hetzner rescue system). Self-play runs with the
same settings as gpu_generations.py's DataGen (6000 nodes, Lichess starts, endgame adjudication), only with the
3-4-5 instead of the 6-piece tables. gpu_generations.py calls `sync` every pass and mixes the files in --dir like its
own self-play files; on a promotion it calls `net all` with the new best network.
State: <dir>/hosts.json.
"""
import argparse
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RECORD = 32
REMOTE = "/root/alieknek"
SOURCES = ["src/main/java/julius/game/chessengine/core", "src/main/java/julius/game/chessengine/syzygy/bridge",
           "src/main/native", "src/main/resources/core/eval-weights.txt"]

SETUP = r"""set -e
cd /root/alieknek
if [ ! -x jdk/bin/java ]; then
  mkdir -p jdk
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/27/ga/linux/x64/jdk/hotspot/normal/eclipse" \
    | tar xz --strip-components=1 -C jdk
fi
if [ "$(ls syzygy 2>/dev/null | grep -c rtb)" -lt 290 ]; then
  mkdir -p syzygy
  tb=http://tablebase.sesse.net/syzygy/3-4-5
  # one file at a time: the mirror answers parallel downloads with 403
  curl -fsS "$tb/" | grep -o 'href="[^"]*\.rtb[wz]"' | cut -d'"' -f2 | sort -u \
    | (cd syzygy && while read f; do [ -s "$f" ] || curl -fsS --retry 3 --retry-delay 2 -o "$f" "$tb/$f" || true; done)
  [ "$(ls syzygy | grep -c rtb)" -ge 290 ] || { echo "syzygy download incomplete"; exit 1; }
fi
rm -rf classes && mkdir -p classes/core data nets
jdk/bin/javac -nowarn --add-modules jdk.incubator.vector -d classes $(find src/main/java -name '*.java')
cp src/main/resources/core/eval-weights.txt classes/core/
gcc -O2 -shared -fPIC -std=gnu11 -DTB_USE_ATOMIC -DTB_NO_HW_POP_COUNT -Ijdk/include -Ijdk/include/linux \
  -Isrc/main/native src/main/native/tbprobe.c src/main/native/jni_bridge.c -o libJSyzygy.so -lm
echo "setup ok: $(jdk/bin/java -version 2>&1 | head -1), $(ls syzygy | wc -l) table files, $(nproc) threads"
"""

START = r"""set -e
cd /root/alieknek
for f in data/*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; done
setsid nohup jdk/bin/java -Xmx16g --add-modules jdk.incubator.vector -Dcore.nnueFile={net} \
  -Dgen.starts=starts.txt -Dgen.startFraction=0.4 -Dgen.syzygy={remote}/syzygy \
  -Dchessengine.syzygy.nativeLibrary={remote}/libJSyzygy.so -Dgen.winScore=2000 -Dgen.winPlies=8 \
  -Dgen.drawPly=160 -Dgen.drawPlies=20 -cp classes julius.game.chessengine.core.tools.DataGen \
  data/{out}.bin $(nproc) 1000000000 {nodes} openings.epd > data/{out}.log 2>&1 < /dev/null &
echo $! > data/{out}.pid
echo "started data/{out}.bin (pid $(cat data/{out}.pid), $(nproc) threads)"
"""


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M')}] {msg}", flush=True)


class Burst:
    def __init__(self, args):
        self.args = args
        self.dir = Path(args.dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.state_path = self.dir / "hosts.json"
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else {}

    def save(self):
        self.state_path.write_text(json.dumps(self.state, indent=1), encoding="utf-8")

    def ssh(self, host: str, cmd: str, stdin=None, stdout=subprocess.PIPE, timeout=600) -> subprocess.CompletedProcess:
        return subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", "-o", "ServerAliveInterval=15",
                               "-o", "ServerAliveCountMax=4", "-i", self.args.key, f"root@{host}", cmd],
                              stdin=stdin, stdout=stdout, stderr=subprocess.PIPE, timeout=timeout)

    def script(self, host: str, text, cmd: str = "bash -s", timeout=600) -> str:
        """Runs cmd on the host with text (str or bytes) on stdin; returns stdout, raises with the output on failure."""
        data = text.encode() if isinstance(text, str) else text
        r = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", "-o", "ServerAliveInterval=15",
                            "-o", "ServerAliveCountMax=4", "-i", self.args.key, f"root@{host}", cmd],
                           input=data, capture_output=True, timeout=timeout)
        if r.returncode != 0:
            raise RuntimeError(f"{host}: " + (r.stdout.decode(errors="replace")[-1500:] +
                                              r.stderr.decode(errors="replace")[-1500:]).strip())
        return r.stdout.decode(errors="replace").strip()

    def upload(self, host: str, local: Path, remote: str):
        with open(local, "rb") as f:
            r = self.ssh(host, f"cat > {REMOTE}/{remote}", stdin=f)
        if r.returncode != 0:
            raise RuntimeError(f"upload {local} -> {host}: {r.stderr.decode(errors='replace')}")

    def net_name(self, net: Path) -> str:
        return hashlib.sha1(net.read_bytes()).hexdigest()[:8]

    def start(self, host: str, net: Path):
        name = self.net_name(net)
        self.upload(host, net, f"nets/{name}.bin")
        out = f"sp-{name}-{time.strftime('%m%d%H%M')}"
        out_line = self.script(host, START.format(net=f"{REMOTE}/nets/{name}.bin", remote=REMOTE, out=out,
                                                  nodes=self.args.nodes), timeout=120)
        log(f"{host}: {out_line} with net {name}")
        h = self.state.setdefault(host, {"runs": []})
        h["runs"].append({"remote": f"data/{out}.bin", "local": str(self.dir / f"cloud-{host.replace('.', '-')}-{out}.bin"),
                          "net": name})
        self.save()

    def setup(self, host: str):
        log(f"{host}: uploading engine sources ({self.args.ref})")
        archive = subprocess.run(["git", "-C", str(REPO), "archive", "--format=tar", self.args.ref, *SOURCES],
                                 capture_output=True, check=True).stdout
        self.script(host, archive, f"mkdir -p {REMOTE}/data {REMOTE}/nets && tar xf - -C {REMOTE}", timeout=300)
        self.upload(host, Path(self.args.starts), "starts.txt")
        self.upload(host, Path(self.args.openings), "openings.epd")
        log(f"{host}: installing Java 27, Syzygy 3-4-5, compiling")
        log(f"{host}: {self.script(host, SETUP, timeout=1800).splitlines()[-1]}")
        self.start(host, Path(self.args.net))

    def sync(self):
        total = 0
        for host, h in self.state.items():
            for run in h["runs"]:
                local = Path(run["local"])
                have = local.stat().st_size if local.exists() else 0
                r = self.ssh(host, f"stat -c %s {REMOTE}/{run['remote']}", timeout=60)
                if r.returncode != 0:
                    log(f"{host}: {run['remote']} not reachable ({r.stderr.decode(errors='replace').strip()[:120]})")
                    continue
                want = int(r.stdout.decode().strip()) // RECORD * RECORD - have
                if want <= 0:
                    continue
                tmp = local.with_suffix(".part")
                with open(tmp, "wb") as f:
                    r = self.ssh(host, f"tail -c +{have + 1} {REMOTE}/{run['remote']} | head -c {want}", stdout=f,
                                 timeout=3600)
                got = tmp.stat().st_size
                if r.returncode == 0 and got == want:
                    with open(local, "ab") as out, open(tmp, "rb") as part:
                        while chunk := part.read(1 << 24):
                            out.write(chunk)
                    total += got // RECORD
                else:
                    log(f"{host}: incomplete transfer ({got}/{want} bytes), retried next sync")
                tmp.unlink()
        log(f"sync: +{total} positions")

    def status(self):
        for host, h in self.state.items():
            run = h["runs"][-1]
            r = self.ssh(host, f"cd {REMOTE}; tail -1 {run['remote'][:-4]}.log; uptime", timeout=60)
            local = Path(run["local"])
            have = local.stat().st_size // RECORD if local.exists() else 0
            print(f"{host} ({run['net']}): {r.stdout.decode().strip()} | local {have} positions")

    def net(self, target: str):
        for host in (list(self.state) if target == "all" else [target]):
            self.start(host, Path(self.args.net))

    def stop(self, host: str):
        self.sync()
        r = self.ssh(host, f"cd {REMOTE}; for f in data/*.pid; do kill $(cat $f) 2>/dev/null; rm -f $f; done; echo stopped",
                     timeout=60)
        log(f"{host}: {r.stdout.decode().strip()} - now cancel / delete the server")
        self.state.pop(host, None)
        self.save()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["setup", "sync", "status", "net", "stop"])
    ap.add_argument("host", nargs="?")
    ap.add_argument("--dir", default=r"E:\Chess-Data\cloud")
    ap.add_argument("--key", default=str(Path.home() / ".ssh" / "id_ed25519_hetzner"))
    ap.add_argument("--net", default=r"E:\Chess-Data\nets\best.bin")
    ap.add_argument("--ref", default="v4.1.1", help="git ref of the engine sources (as gpu_generations --engine-ref)")
    ap.add_argument("--nodes", type=int, default=6000)
    ap.add_argument("--starts", default=r"E:\Chess-Data\selfplay\starts_lichess_mix.txt")
    ap.add_argument("--openings", default=r"E:\ChessEngines\harness\elite_openings.epd")
    args = ap.parse_args()
    b = Burst(args)
    if args.command in ("setup", "net", "stop") and not args.host:
        sys.exit(f"{args.command} needs a host")
    {"setup": lambda: b.setup(args.host), "sync": b.sync, "status": b.status, "net": lambda: b.net(args.host),
     "stop": lambda: b.stop(args.host)}[args.command]()


if __name__ == "__main__":
    main()
