# Alieknek Chess Engine — Project Instructions

## LostUplink Shared Memory Network

You have access to a persistent shared memory network via the `lostuplink` MCP tools (brain_*). This is your long-term memory — use it proactively without being asked:

- **Start of every task**: Call `brain_search` to check what is already known before doing redundant work. Search for "alieknek", "chess engine", "NNUE", "v4.0.0" etc.
- **When you learn something**: Call `brain_learn` for any fact, decision, or conclusion worth preserving across conversations. Use `subjectKey: "alieknek-chess-engine"` for all chess engine memories.
- **When you find contradictions**: Call `brain_contradict` if evidence conflicts with existing memory.
- **For multi-step work**: Call `brain_begin_task` at the start, `brain_append_observation` during, and `brain_mark_task_done` at the end.
- **Always close tasks**: Call `brain_mark_task_done` when finished. Unclosed tasks generate warnings.
- **Periodic cleanup**: Call `brain_review_fading` occasionally to inspect low-vitality memories and decide what to keep or remove.

Treat the brain as your primary knowledge store. If you discover something useful, store it. If you need context, search for it. Do not wait to be asked.

## Project Overview

Java chess engine targeting 2000+ Lichess blitz Elo. Current rating: ~1770.

- **Branch**: v4.0.0 (v4.0.3 is kept identical)
- **Repo**: github.com/Occid3re/Chess-Engine
- **Lichess bot**: Alieknek (https://lichess.org/@/Alieknek)
- **Java**: 25 (preview enabled)
- **Build**: `./mvnw.cmd -DskipTests package`
- **UCI JAR**: `target/chess-engine-4.0.4-uci.jar`

## New core engine (DEFAULT since 2026-10-13)

`UciMain` runs the new core in `julius.game.chessengine.core` unless `-Dchessengine.core=legacy` is set.
It has no dependencies (plain `javac` builds it) and does ~0.8-1M nps per thread vs ~6k for the legacy AI.

- Position/MoveGen/Attacks (magic bitboards, perft-verified), Searcher (negamax PVS, int centipawns, TT, NMP,
  LMR, RFP, ProbCut, singular ext., history/continuation history), SearchManager (Lazy SMP, time management)
- Eval: tapered hand-crafted eval, weights in `src/main/resources/core/eval-weights.txt`,
  fitted by `core.tuning.EvalTuner` to `E:\Chess-Data\tuning\elite2M.txt` (fen;whiteCp;wdl)
- NNUE (DEFAULT when `core/nnue.bin` is bundled; `-Dcore.useNnue=false` or UCI `UseNNUE false` = hand-crafted):
  (768->256)x2->1 SCReLU, QA=255/QB=256, trained by `core.nnue.Trainer` (CPU, Java, ~20 s/epoch on 16M positions)
  on `E:\Chess-Data\tuning\elite2M.txt` + self-play from `core.tools.DataGen` (`E:\Chess-Data\selfplay\sp_all.txt`,
  13.7M positions, 6000 nodes/move), lambda 0.3, K 230, 15 epochs. Measured at 10+0.1: net on 8.8M positions
  +38±22 Elo vs hand-crafted (641 games); net on 15.9M (bundled) +39±32 vs that one. More data = next gain.
  Keep int[] arrays + per-feature weight rows: other layouts do not vectorise.
- Results (10+0.1, 1 thread): 40-0 vs 4.0.0, 40-0 vs 3.6.9, 52-6-2 vs Stockfish UCI_Elo 2600; 4 threads vs 1: +168 Elo
- Match harness: `E:\ChessEngines\harness\` (sprt.sh, match.sh, vs_sf.sh, smp.sh, snap.sh, elite_openings.epd)
- Tests: `./mvnw.cmd -Dtest=CoreEngineTest test`
- Server deployment (play.juliussze.at, Occid3re/play repo): `alieknek` service with play window, switch and watch page
  (`src/main/resources/py/alieknek_service.py`, `watch/`)

## Legacy 4.x architecture (`-Dchessengine.core=legacy`)

- Bitboard move generation
- Alpha-beta with PVS, LMR, null-move, aspiration windows, futility, razoring, ProbCut
- Lazy SMP (dedicated threads in blocking search, NOT shared thread pool)
- Syzygy 6-piece tablebases via JNI
- Opening book (PGN-based)
- Evaluation: 5 hand-crafted modules (Material, PawnStructure, Activity, KingSafety, Threat)
- Tuning: `src/main/resources/tuning/seed-tunings.yaml` (Alien-Termination config)
- NNUE: HalfKP architecture in progress (eval.mode=nnue, not production yet)

## Key Decisions & Lessons (IMPORTANT — read before making changes)

1. **PSTs are DISABLED** (`pstScale=0.0`). They regressed -89 Elo due to double-counting with ActivityModule. Do NOT re-enable without joint eval retuning.
2. **Null-move at PV nodes is ENABLED** (`nullAtPv=true`). Disabling it costs -47 Elo. v3.6.9 had it enabled.
3. **Root fanout ratio is 1.0**. Lowering it (e.g. 0.25) cripples root parallelism at low thread counts. Cost: -50 Elo.
4. **Lazy SMP in blocking search uses DEDICATED threads**, not the shared pool. The shared pool checks `keepCalculating` which is false in blocking mode → workers abort immediately. See commit 9004eb06 for the original bug.
5. **Static BestMoveSearchTest can hide Elo regressions**. Always gate improvements on 40+ game self-play matches at 10+0.1.
6. **v3.6.9 tuning on v4 code is -23 Elo**. The Alien-Termination tuning is optimized for the current codebase.
7. **respondWithMoveOrTerminal must NOT check isTerminal()** — it can see stale state from previous games. Fixed in commit 9f0aaed5.
8. **Lichess bot needs auto-reconnect** — SSE stream drops randomly. Retry loop in `__main__`.
9. **NNUE lazy rebuild is faster than incremental in Java** — cache locality of single accumulator beats ply-indexed arrays. Don't add push/pop copy overhead.
10. **blendscale=903.96 is correct**. Changing to 256 costs -35 Elo. The genetic tuner found this value.
11. **Endgame scaling features are worth +12 Elo** — keep them enabled.
12. **In-game Lichess calls bypass the global pacing** (v4.0.1). `BOT_QPS=0.5` paced *every* non-stream call,
    including posting a move, so the bot never moved faster than one move per 2 s: 60+0 bullet was lost on
    time around move 30, blitz endgames ran at the 2 s floor. Moves, abort and resign now skip the limiter
    and the mutex and use a lean adapter (no status retries, no Retry-After sleep). Keep it that way.
13. **Time budget per move** (`SearchManager.allocate`, v4.0.1): moves-to-go 40 at the start shrinking to 16,
    optimum ≤ 20 % and maximum ≤ 40 % of the clock, under 10 s a twentieth plus most of the increment. The
    old `left/32`, max 45 % spent the clock early: in 3+2 only 20 s were left after move 40.
14. **Weekly play schedule** (`alieknek_service.py`, v4.0.2): `ALIEKNEK_HOURS` / `botctl hours` take weekday groups
    (`Mo-Fr 01:00-05:00; Sa-So 00:00-08:00`, `always`, `never`; days not named do not play; a window may
    cross midnight). The control API also takes `schedule` (seven lists of [start, end] minutes) and reports
    `schedule`, `windowEndsAt`, `nextWindowAt` – the play app's week editor builds on that.
    `windowEndsAt` follows a window across midnight into the next day's 00:00 start (v4.0.3) and is
    null for `always` – before that the app showed "bis 00:00 Uhr" for a bot that never stops.

## Eval Modes

- `-Dchessengine.eval.mode=classic` (DEFAULT, strongest currently)
- `-Dchessengine.eval.mode=neural` (dense 71-feature residual NN, opt-in, -158 Elo in self-play)
- `-Dchessengine.eval.mode=nnue` (HalfKP NNUE, in development, needs more training data)

## NNUE Development

- **Architecture**: HalfKP 40960 → 128 → ClippedReLU → 32 → ClippedReLU → 1
- **Java classes**: NNUEFeatures, NNUEAccumulator, NNUENetwork, NNUEModule (in evaluation/nn/)
- **Training**: scripts/nn-training/train_nnue.py (PyTorch, EmbeddingBag sparse L1, hybrid WDL+eval loss)
- **Data gen**: scripts/nn-training/generate_nnue_data.py (parallel SF labeling from PGN)
- **Evolution**: scripts/nn-training/evolve_nnue.py (self-play + mutation + selection loop)
- **Current NPS gap**: NNUE depth 5 vs classic depth 6 in 5s (1 depth behind)
- **Training data**: Lichess Elite DB (2500+ rated) at E:/Chess-Data/lichess-elite/pgns/
- **Next step**: Train on 2M elite positions, then run evolution loop

## Testing

- **BestMoveSearchTest**: `./mvnw.cmd -Dtest=BestMoveSearchTest test` (baseline: 80/97)
- **Self-play**: CuteChess CLI at `"C:\Program Files (x86)\Cute Chess\cutechess-cli.exe"`
- **Engine slots**: E:\ChessEngines\ (v4.0.0-classic, v3.6.9, v3.9.1, etc.)
- **Stockfish**: E:\ChessEngines\stockfish_17.1\stockfish-windows-x86-64-avx2.exe

## Bot Deployment

```bash
BOT_TC=blitz CHESSENGINE_THREADS=16 CHESSENGINE_LAZY_THREADS=8 \
  /c/Python38/python.exe -u src/main/resources/py/lichess_bot.py
```

Python 3.8 required (Anaconda's 3.7 has broken pyopenssl). LICHESS_TOKEN must be set in env.
