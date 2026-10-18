"""Shared cutechess-cli helpers for the tuning tools (spsa.py, generations.py).

The result parser is the robust per-game tally from the former cutechess_tuning_loop.py: it counts the
"Finished game" lines (independent of which engine is listed first) and falls back to the "Score of"
summary. Engines are the core UCI engine started as `java -cp <classes> julius.game.chessengine.core.Uci`.
"""
import math
import os
import re
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence

CUTECHESS = os.environ.get("CUTECHESS", r"C:\Program Files (x86)\Cute Chess\cutechess-cli.exe")
OPENINGS = os.environ.get("TUNE_OPENINGS", r"E:\ChessEngines\harness\elite_openings.epd")

SCORE_PATTERN = re.compile(
    r"Score of (?P<first>.+?) vs (?P<second>.+?): (?P<wins>\d+) - (?P<losses>\d+) - (?P<draws>\d+)"
)
FINISHED_RE = re.compile(
    r"^Finished game \d+ \((?P<left>.+?) vs (?P<right>.+?)\):\s*(?P<res>1-0|0-1|1/2-1/2)"
)
SPRT_RE = re.compile(r"SPRT: llr (?P<llr>-?[\d.]+).*?(?P<verdict>H0 was accepted|H1 was accepted)?$")


@dataclass
class MatchResult:
    engine_name: str
    opponent_name: str
    wins: int
    losses: int
    draws: int
    duration_s: float = 0.0
    stdout: str = field(default="", repr=False)
    verdict: Optional[str] = None  # "H1" / "H0" for SPRT runs

    @property
    def total_games(self) -> int:
        return self.wins + self.losses + self.draws

    @property
    def points_fraction(self) -> float:
        return 0.0 if self.total_games == 0 else (self.wins + 0.5 * self.draws) / self.total_games

    @property
    def elo_diff(self) -> float:
        return elo_from_score(self.points_fraction)

    def summary(self) -> str:
        return (f"{self.engine_name} vs {self.opponent_name}: {self.wins}-{self.losses}-{self.draws} "
                f"({self.points_fraction * 100:.1f}%, {self.elo_diff:+.1f} Elo) in {self.total_games} games")


def elo_from_score(score: float) -> float:
    """Logistic Elo difference for a points fraction (clamped so 0 % / 100 % stay finite)."""
    s = min(max(score, 1e-3), 1 - 1e-3)
    return -400.0 * math.log10(1.0 / s - 1.0)


def parse_score(stdout: str, engine_name: str, opponent_name: str, duration_s: float = 0.0) -> MatchResult:
    """Tally results from per-game lines (robust to engine order), falling back to the summary line."""
    wins = losses = draws = 0
    for raw in stdout.splitlines():
        m = FINISHED_RE.search(raw.strip())
        if not m:
            continue
        left, right, res = m.group("left").strip(), m.group("right").strip(), m.group("res")
        if {left, right} != {engine_name, opponent_name}:
            continue
        engine_on_left = left == engine_name
        if res == "1/2-1/2":
            draws += 1
        elif (res == "1-0") == engine_on_left:
            wins += 1
        else:
            losses += 1
    verdict = None
    for raw in stdout.splitlines():
        if "H1 was accepted" in raw:
            verdict = "H1"
        elif "H0 was accepted" in raw:
            verdict = "H0"
    if wins + losses + draws > 0:
        return MatchResult(engine_name, opponent_name, wins, losses, draws, duration_s, stdout, verdict)

    engine_first = opponent_first = None
    for raw in stdout.splitlines():
        m = SCORE_PATTERN.search(raw.strip())
        if not m:
            continue
        first, second = m.group("first").strip(), m.group("second").strip()
        if first == engine_name and second == opponent_name:
            engine_first = m
        elif first == opponent_name and second == engine_name:
            opponent_first = m
    if engine_first is not None:
        w, l, d = int(engine_first.group("wins")), int(engine_first.group("losses")), int(engine_first.group("draws"))
    elif opponent_first is not None:
        w, l, d = int(opponent_first.group("losses")), int(opponent_first.group("wins")), int(opponent_first.group("draws"))
    else:
        raise ValueError(f"Could not parse results for ({engine_name} vs {opponent_name}).")
    return MatchResult(engine_name, opponent_name, w, l, d, duration_s, stdout, verdict)


@dataclass
class Engine:
    """A core engine build: a classes directory (with core/nnue.bin, core/eval-weights.txt) plus options."""
    name: str
    classes: str
    options: Dict[str, object] = field(default_factory=dict)
    jvm: Sequence[str] = ("-Xms256m", "-Xmx512m", "-XX:+UseParallelGC", "--add-modules", "jdk.incubator.vector")
    tune: bool = False

    def args(self) -> List[str]:
        cmd = ["java", *self.jvm]
        if self.tune:
            cmd.append("-Dcore.tune=true")
        cmd += ["-cp", self.classes, "julius.game.chessengine.core.Uci"]
        out = ["-engine", f"name={self.name}", f"cmd={cmd[0]}"]
        out += [f"arg={a}" for a in cmd[1:]]
        out += [f"option.{k}={v}" for k, v in self.options.items()]
        return out


def run_match(a: Engine, b: Engine, tc: str, rounds: int, concurrency: int, *,
              sprt: Optional[Sequence[float]] = None, pgn: Optional[Path] = None,
              hash_mb: int = 32, extra: Sequence[str] = ()) -> MatchResult:
    """Plays `rounds` colour-swapped game pairs from the balanced opening suite (or until the SPRT stops)."""
    cmd = [CUTECHESS, *a.args(), *b.args(),
           "-each", "proto=uci", f"tc={tc}", f"option.Hash={hash_mb}", "option.Threads=1", "timemargin=300",
           "-openings", f"file={OPENINGS}", "format=epd", "order=random",
           "-repeat", "-games", "2", "-rounds", str(rounds), "-concurrency", str(concurrency),
           "-draw", "movenumber=40", "movecount=8", "score=8",
           "-resign", "movecount=4", "score=800", "twosided=true", "-recover", *extra]
    if sprt:
        elo0, elo1 = sprt[0], sprt[1]
        cmd += ["-sprt", f"elo0={elo0}", f"elo1={elo1}", "alpha=0.05", "beta=0.05"]
    if pgn:
        cmd += ["-pgnout", str(pgn)]
    started = time.time()
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    result = parse_score(proc.stdout, a.name, b.name, time.time() - started)
    if "disconnects" in proc.stdout or "stalls" in proc.stdout:
        raise RuntimeError(f"engine failure during {a.name} vs {b.name}:\n" + proc.stdout[-2000:])
    return result
