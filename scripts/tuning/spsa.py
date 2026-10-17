#!/usr/bin/env python3
"""SPSA tuning of the core engine's search parameters (core/Tune.java) through cutechess.

Simultaneous perturbation stochastic approximation as used by Fishtest: every iteration perturbs ALL
parameters at once by +-c_k (random signs), plays colour-swapped game pairs between the "+" and the "-"
engine from the balanced opening suite, and moves each parameter by
    theta_i += a_k * (wins - losses of "+") / (c_k,i * delta_i).
Schedules: c_k = c_i / k^0.101, a_k = a / (A + k)^0.602 with A = 10 % of the iterations and a chosen so
that a parameter moves about r_end * c_i^2 per game at the end (Fishtest's defaults).

Parameters (name, default, min, max, c) are read from core/Tune.java, so the script never drifts from
the engine. State is saved after every iteration (--state), so a run can be stopped and resumed. The
result must still pass an SPRT against the defaults (see --verify) before it becomes the new default.

Usage:
  python scripts/tuning/spsa.py --classes E:/ChessEngines/v5-dev/tune/classes --iterations 800 --pairs 8
  python scripts/tuning/spsa.py --classes ... --verify            # SPRT tuned values vs defaults
"""
import argparse
import json
import random
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cutechess import Engine, run_match  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
TUNE_JAVA = REPO / "src" / "main" / "java" / "julius" / "game" / "chessengine" / "core" / "Tune.java"
ADD_RE = re.compile(r'add\("(?P<name>\w+)",\s*\w+,\s*(?P<min>-?\d+),\s*(?P<max>-?\d+),\s*(?P<c>[\d.]+)\)')
FIELD_RE = re.compile(r"public static int (?P<name>\w+) = (?P<value>-?\d+);")


def read_params(path: Path = TUNE_JAVA) -> list:
    """[{name, default, min, max, c}] from Tune.java (the add(...) registry and the field defaults)."""
    text = path.read_text(encoding="utf-8")
    defaults = {m["name"]: int(m["value"]) for m in FIELD_RE.finditer(text)}
    params = []
    for m in ADD_RE.finditer(text):
        name = m["name"]
        params.append({"name": name, "default": defaults[name], "min": int(m["min"]), "max": int(m["max"]),
                       "c": float(m["c"])})
    if not params:
        raise SystemExit(f"no tunables found in {path}")
    return params


def schedules(k: int, iterations: int, c: float, r_end: float):
    """(c_k, a_k) for iteration k (1-based) with Fishtest's exponents."""
    alpha, gamma = 0.602, 0.101
    big_a = 0.1 * iterations
    c_end = c  # the configured c is the perturbation at the end of the run
    c_k = c_end * (iterations / k) ** gamma
    a_end = r_end * c_end ** 2
    a = a_end * (big_a + iterations) ** alpha
    a_k = a / (big_a + k) ** alpha
    return c_k, a_k


def clamp(p: dict, v: float) -> float:
    return max(p["min"], min(p["max"], v))


def load_state(path: Path, params: list) -> dict:
    if path.exists():
        state = json.loads(path.read_text(encoding="utf-8"))
        # parameters added to Tune.java since the run started begin at their default
        for p in params:
            state["theta"].setdefault(p["name"], float(p["default"]))
        return state
    return {"k": 0, "theta": {p["name"]: float(p["default"]) for p in params}, "history": []}


def tune(args) -> None:
    params = read_params()
    state_path = Path(args.state)
    state = load_state(state_path, params)
    rng = random.Random(args.seed + state["k"])
    print(f"SPSA over {len(params)} parameters, iteration {state['k']} of {args.iterations}")
    while state["k"] < args.iterations:
        k = state["k"] + 1
        plus, minus, deltas = {}, {}, {}
        for p in params:
            c_k, _ = schedules(k, args.iterations, p["c"], args.r_end)
            d = rng.choice((-1, 1))
            deltas[p["name"]] = d
            theta = state["theta"][p["name"]]
            plus[p["name"]] = int(round(clamp(p, theta + c_k * d)))
            minus[p["name"]] = int(round(clamp(p, theta - c_k * d)))
        a = Engine("plus", args.classes, plus, tune=True)
        b = Engine("minus", args.classes, minus, tune=True)
        result = run_match(a, b, args.tc, args.pairs, args.concurrency)
        diff = result.wins - result.losses
        for p in params:
            c_k, a_k = schedules(k, args.iterations, p["c"], args.r_end)
            name = p["name"]
            state["theta"][name] = clamp(p, state["theta"][name] + a_k * diff / (c_k * deltas[name]))
        state["k"] = k
        state["history"].append({"k": k, "w": result.wins, "l": result.losses, "d": result.draws,
                                 "time": round(result.duration_s, 1)})
        state_path.parent.mkdir(parents=True, exist_ok=True)
        state_path.write_text(json.dumps(state, indent=1), encoding="utf-8")
        if k % 10 == 0 or k == args.iterations:
            moved = ", ".join(f"{p['name']}={state['theta'][p['name']]:.1f}" for p in params)
            games = sum(h["w"] + h["l"] + h["d"] for h in state["history"])
            print(f"[{time.strftime('%H:%M')}] iter {k}/{args.iterations} ({games} games): {moved}", flush=True)


def verify(args) -> None:
    """SPRT of the tuned values against the defaults (both engines from the same classes)."""
    params = read_params()
    state = load_state(Path(args.state), params)
    tuned = {p["name"]: int(round(state["theta"][p["name"]])) for p in params}
    changed = {n: v for n, v in tuned.items() if v != next(p["default"] for p in params if p["name"] == n)}
    print("tuned values that differ from the defaults:", changed)
    a = Engine("tuned", args.classes, tuned, tune=True)
    b = Engine("default", args.classes, {}, tune=True)
    result = run_match(a, b, args.tc, args.verify_rounds, args.concurrency, sprt=(0, 5))
    print(result.summary(), "SPRT:", result.verdict)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--classes", required=True, help="compiled core classes dir (with core/nnue.bin)")
    ap.add_argument("--state", default=r"E:\Chess-Data\tuning\spsa-state.json")
    ap.add_argument("--iterations", type=int, default=800)
    ap.add_argument("--pairs", type=int, default=8, help="game pairs per iteration")
    ap.add_argument("--concurrency", type=int, default=10)
    ap.add_argument("--tc", default="8+0.08")
    ap.add_argument("--r-end", type=float, default=0.002)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--verify-rounds", type=int, default=2000)
    args = ap.parse_args()
    verify(args) if args.verify else tune(args)


if __name__ == "__main__":
    main()
