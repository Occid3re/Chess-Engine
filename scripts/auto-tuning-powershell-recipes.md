# Chess Engine Auto‑Tuning Loop — PowerShell Recipes & Docs

This guide gives you **ready‑to‑paste PowerShell commands** for the automated tuning loop (`auto_tuning_loop.py`), plus notes on flags, regex filters, JVM/engine settings, and troubleshooting.

> All examples assume your repo lives at `C:\Development\Chess-Engine` and tests at `julius.game.chessengine.ai.BestMoveSearchTest`. Adjust paths as needed.

---

## Quick Start (Baseline)

```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --java-release 25 `
  --preview `
  --jvm-gc zgc `
  --tt-mb 1024 `
  --engine-threads auto `
  --lazy-threads auto `
  --root-par-limit auto `
  --xms auto `
  --xmx auto `
  --plan-concurrent 1 `
  --mut-frac 0.30 --mut-frac-min 0.20 --mut-frac-max 0.45 `
  --accept-worse --accept-temp 0.08 `
  --noimp-reheat 12 --reheat-factor 1.7 `
  --priority-duration-metric durationMsP95 `
  --duration-bonus-threshold 0.20 `
  --max-failure-regress 1 --max-error-regress 0 `
  --duration-weight 1.1 `
  --freeze-after 10 `
  --extra-maven-args -q
```

---

## What you ran (tight eval‑only sweep)

Your last command focuses on **evaluation-only** tuning with **very small** mutation steps:

```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --java-release 25 `
  --preview `
  --accept-worse `
  --accept-temp 0.08 `
  --mut-frac 0.02 `
  --mut-frac-min 0.02 `
  --mut-frac-max 0.04 `
  --freeze-after 10 `
  --allow-pattern "^(evaluation\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.)" `
  --noimp-reheat 12 `
  --reheat-factor 1.7 `
  --jvm-gc zgc `
  --tt-mb 1024 `
  --engine-threads 1 `
  --lazy-threads 1 `
  --root-par-limit 48 `
  --xms auto `
  --xmx auto `
  --plan-concurrent 1 `
  --priority-duration-metric durationMsP95 `
  --duration-bonus-threshold 0.20 `
  --max-failure-regress 1 `
  --max-error-regress 0 `
  --duration-weight 1.1 `
  --git-commit `
  --extra-maven-args -q
```

**When to use**: you want gentle, conservative changes to eval weights without touching search/move ordering. Good when your evaluation is already close and you’re polishing.

---

## Scenario Recipes

> **Tip**: the script normalizes parameter keys to lowercase before matching; regexes below are **lowercase** and **anchored** with `^` for safety.

### A) Eval‑Only (no search / move ordering)
Focuses on eval weights (modules + *pawnstructure/activity/etc.*).

```powershell
--allow-pattern "^(evaluation\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.)"
```

Full command template:
```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --preview `
  --mut-frac 0.15 --mut-frac-min 0.10 --mut-frac-max 0.30 `
  --freeze-after 10 `
  --allow-pattern "^(evaluation\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.)" `
  --extra-maven-args -q
```

---

### B) Search‑Only (no eval / move ordering)
Great for speed/strength from pruning, LMR/LMP, aspiration windows, etc.

```powershell
--allow-pattern "^search\."
```

Suggested conservative sweep:
```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --preview `
  --mut-frac 0.20 --mut-frac-min 0.10 --mut-frac-max 0.35 `
  --allow-pattern "^search\." `
  --extra-maven-args -q
```

---

### C) Speed‑First Search (LMR/LMP/FP + pruning)
Prioritize throughput while guarding quality.

```powershell
--allow-pattern "^(search\.(lmr|lmp|fp|iid|seeprunenearrootply|historyreductionmax|maxcheckextensionstreak))"
```

---

### D) Move Ordering Focus
Work on categories, SEE/MVV‑LVA weights; avoid huge constants.

```powershell
--allow-pattern "^moveordering\." `
--deny-pattern   "^moveordering\.maxscore$"
```

---

### E) Tactics/Accuracy Push
Root heuristics + ASP window + SEE/capture sorting (risky, powerful).

```powershell
--allow-pattern "^(search\.(asp|root|lmr)|moveordering\.(capture|promotion|capturemvv|capturesee|promotionsee|category\.(capturegood|captureequal|capturebad)))" `
--deny-pattern   "^moveordering\.maxscore$"
```

---

### F) Stable Speed (avoid root heuristics volatility)
Good for consistent timings.

```powershell
--allow-pattern "^(moveordering\.|search\.(lmr|lmp|fp|iid|seeprunenearrootply|historyreductionmax))" `
--deny-pattern   "^(moveordering\.maxscore$|search\.root)"
```

---

### G) Endgame‑Centric
Leans into endgame eval and null‑move/qs tuning.

```powershell
--allow-pattern "^(evaluation\.modules\..*\.endgame$|activity\.endgame|pawnstructure\.(passedpawn|passedpawnfreepath|ownkingblockspassedpawn)|kingsafety\.backrankweaknessendgamepenalty|search\.(null|qsmaxdeltapawn))"
```

---

### H) Opening/Middlegame‑Centric
Development, king safety shell, midgame activity.

```powershell
--allow-pattern "^(evaluation\.modules\..*\.midgame$|activity\.midgame|development\.|kingsafety\.(missingpawnshieldpenalty|halfopenfilepenalty|openfilepenalty|defenderbonus))"
```

---

### I) ASP‑Only Lab
Target aspiration logic in isolation.

```powershell
--allow-pattern "^search\.asp"
```

---

### J) LMR‑Only Lab
Target late move reductions in isolation.

```powershell
--allow-pattern "^search\.lmr"
```

---

### K) Broad Exploration w/ Guardrails
Covers all families but blocks risky toggles & giant constants.

```powershell
--allow-pattern "^(evaluation\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.|moveordering\.|search\.)" `
--deny-pattern   "^(moveordering\.maxscore$|search\.preferfastmate$|search\.tbtiebreak$)"
```

---

## Mutation Strategy & Freezing

- `--mut-frac X`: fraction of parameters mutated per iteration (adaptive within `[--mut-frac-min, --mut-frac-max]`).
- `--freeze-after N`: if a param is mutated **N** times without improvement, it gets **auto‑frozen** (search space shrinks over time).
- Reheating: after `--noimp-reheat` non‑improving iterations the loop raises temperature by `--reheat-factor` to escape plateaus.

Recommended starting points:
- **Eval‑only**: `mut-frac 0.10–0.25`
- **Search‑only**: `mut-frac 0.20–0.40`
- **Narrow polish**: `mut-frac 0.02–0.06` (what you used)

---

## JVM & Engine Parity Notes

- `--jvm-gc zgc` is default; alternatives: `shenandoah`, `g1`.
- `--xms/--xmx auto` size heap from RAM/TT size; ZGC gets `SoftMaxHeapSize` to uncommit under pressure.
- Parallelism planning (parity with lichess_bot):
  - `--engine-threads`, `--lazy-threads`, `--root-par-limit`: set to `auto` or explicit numbers.
  - `--apc`, `--concgc`, `--cicomp` can be left `auto`.
- Tablebases: the loop forwards `chessengine.syzygy.*` envs to the test JVM; keep these **constant** during eval‑weight tuning.

---

## Regex & PowerShell Quoting

- The script matches **lowercased** keys; write regex in **lowercase**.
- Anchor at start: `^search\.` is safer than `search`.
- Escape `.` as `\.` in regex (`search\.` not `search.`).
- In PowerShell, **double‑quotes** are fine for regex; backticks `` ` `` continue lines.

Example:
```powershell
--allow-pattern "^(search\.(lmr|lmp|fp)|moveordering\.)"
```

---

## Useful Flags (TL;DR)

| Flag | What it does |
|------|---------------|
| `--accept-worse` | Enables simulated annealing; can accept worse candidates probabilistically. |
| `--accept-temp` | Temperature for annealing acceptance decisions. |
| `--priority-duration-metric` | Which timing metric to optimize first (e.g., `durationMsP95`). |
| `--duration-bonus-threshold` | Accept candidate if this metric drops by ≥ threshold **and** failures/errors within limits. |
| `--max-failure-regress`, `--max-error-regress` | Guardrails for time‑bonus acceptance. |
| `--git-commit` | Auto‑commit the best tuning file when improved. |
| `--extra-maven-args` | Pass through maven flags (e.g., `-q`, `-pl :engine -am`). |

---

## Troubleshooting

- **No XML reports found**: add `--extra-maven-args -q` or ensure you’re running the right module; for submodules use:  
  `--extra-maven-args -pl :engine -am`
- **Regex matches nothing**: remove the anchor or widen families; verify keys in your YAML (normalized/flattened names).  
- **Too slow / timeouts**: reduce `--engine-threads`/`--lazy-threads`, or temporarily raise `--duration-bonus-threshold` to reward speed.  
- **Stalls**: lower `--mut-frac`, use `--noimp-reheat`/`--reheat-factor`, or widen `--mut-frac-max`.  
- **Over‑pruning breaks correctness**: deny risky families (e.g., `--deny-pattern "^search\.root"`).

---

## Copy‑Ready Bundles

### Balanced Search + Move Ordering

```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --preview `
  --mut-frac 0.25 --mut-frac-min 0.15 --mut-frac-max 0.40 `
  --freeze-after 10 `
  --allow-pattern "^(search\.(lmr|lmp|fp|iid|asp)|moveordering\.)" `
  --deny-pattern   "^moveordering\.maxscore$" `
  --accept-worse --accept-temp 0.08 `
  --noimp-reheat 12 --reheat-factor 1.7 `
  --priority-duration-metric durationMsP95 `
  --duration-bonus-threshold 0.20 `
  --max-failure-regress 1 --max-error-regress 0 `
  --duration-weight 1.1 `
  --jvm-gc zgc --tt-mb 1024 --xms auto --xmx auto `
  --engine-threads auto --lazy-threads auto --root-par-limit auto `
  --plan-concurrent 1 `
  --extra-maven-args -q
```

### Eval Polish (Small Steps)

```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --preview `
  --mut-frac 0.03 --mut-frac-min 0.02 --mut-frac-max 0.06 `
  --freeze-after 12 `
  --allow-pattern "^(evaluation\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.)" `
  --accept-worse --accept-temp 0.08 `
  --priority-duration-metric durationMsP95 `
  --duration-bonus-threshold 0.18 `
  --max-failure-regress 1 --max-error-regress 0 `
  --jvm-gc zgc --tt-mb 1024 --xms auto --xmx auto `
  --engine-threads 1 --lazy-threads 1 --root-par-limit 48 `
  --extra-maven-args -q
```

### ASP Window Lab

```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --preview `
  --mut-frac 0.20 --mut-frac-min 0.10 --mut-frac-max 0.35 `
  --freeze-after 10 `
  --allow-pattern "^search\.asp" `
  --accept-worse --accept-temp 0.10 `
  --priority-duration-metric durationMsP95 `
  --duration-bonus-threshold 0.22 `
  --extra-maven-args -q
```

---

## Versioning & Artifacts

The loop will:
- Make a timestamped backup of your seed file.
- Save each iteration’s YAML under `logs/iteration-configs/Iteration-<n>-<successes>-<secs>.yaml`.
- Write a `*.best.yaml` checkpoint beside your seed file.
- Optionally `git commit` the improved best (with `--git-commit`).

---

Happy tuning! 🛠️♟️
