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

- **Branch**: v4.0.0
- **Repo**: github.com/Occid3re/Chess-Engine
- **Lichess bot**: Alieknek (https://lichess.org/@/Alieknek)
- **Java**: 25 (preview enabled)
- **Build**: `./mvnw.cmd -DskipTests package`
- **UCI JAR**: `target/chess-engine-4.0.0-uci.jar`

## Architecture

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
