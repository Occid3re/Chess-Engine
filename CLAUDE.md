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

Java chess engine, plays on Lichess as the bot Alieknek: blitz ~2480 (October 2026, up from ~1770 with the
legacy AI before the core engine).

- **Branches**: one per release, named after the version (v4.0.0, v4.0.1, ... v4.0.9, v4.1.0). Each digit runs 0-9:
  after x.y.9 comes x.(y+1).0 (never 4.0.10). Work for the next
  release goes on a new branch `vX.Y.Z` cut from the previous release branch; never push a newer
  version onto an older version's branch. Bump `pom.xml` and `Uci.NAME` to the same number.
- **Repo**: github.com/Occid3re/Chess-Engine
- **Lichess bot**: Alieknek (https://lichess.org/@/Alieknek)
- **Java**: builds with 25 (preview enabled); the server runs Temurin 27 (measured 2-4 % faster than 25, 2026-10-18)
- **Build**: `./mvnw.cmd -DskipTests package`
- **UCI JAR**: `target/chess-engine-<version>-uci.jar` (main class `uci.UciMain` -> `core.Uci`)
- **Tests**: `./mvnw.cmd test` (CoreEngineTest, TimeManagementTest, Syzygy bridge tests);
  Python: `scripts/tests` (pytest; not installed locally, run it in the `lostopf-alieknek` image with
  `/opt/venv/bin/pip install pytest`)

## Core engine (`julius.game.chessengine.core`)

No dependencies (plain `javac` builds it), ~0.8-1M nps per thread.

- Position/MoveGen/Attacks (magic bitboards, perft-verified), Searcher (negamax PVS, int centipawns, TT, NMP,
  LMR, RFP, ProbCut, singular ext., history/continuation history), SearchManager (Lazy SMP, time management)
- Search constants live in `core/Tune.java` (name, default, min, max, SPSA step). With `-Dcore.tune=true` they
  are UCI spin options; `scripts/tuning/spsa.py` reads them from that file, so add new tunables there.
- Eval: tapered hand-crafted eval, weights in `src/main/resources/core/eval-weights.txt`,
  fitted by `core.tuning.EvalTuner` to `E:\Chess-Data\tuning\elite2M.txt` (fen;whiteCp;wdl)
- NNUE (DEFAULT when `core/nnue.bin` is bundled; `-Dcore.useNnue=false` or UCI `UseNNUE false` = hand-crafted,
  `-Dcore.nnueFile=<path>` loads another net):
  (768->256)x2->1 SCReLU with 8 material output buckets (file version 2), QA=255/QB=256, trained by
  `core.nnue.Trainer -Dtrain.buckets=8` (CPU, Java, ~70 s/epoch on 66M samples), lambda 0.3, K 230, 15 epochs.
  Data in `E:\Chess-Data\`: tuning/elite2M.txt, selfplay/sp_all.txt (13.7M, HCE engine), v5b_nnue.txt (17.6M, NNUE
  engine), v5c_endgame.txt (19.3M: half the games from 8-20-piece starts, 6-man Syzygy results, late adjudication)
  and eg_extra.txt (13.5M positions with <=10 pieces, i.e. endgames counted twice). That mix matches the real
  server games (39% endgame positions). History at 10+0.1: first net +38±22 vs hand-crafted; 15.9M-position net
  +39±32 vs that (4.0.0-4.0.4); bucketed endgame-weighted net (4.0.5) +86±33 vs the 4.0.4 net (SPRT H1, 235 games);
  generation 1 of `scripts/tuning/generations.py` (4.0.7: +15M self-play positions by the 4.0.5 net, sp_all dropped)
  +26.7 vs 4.0.5/4.0.6 (SPRT H1, 1682 games). A 384-wide net lost to 256 (-5±12, H0, 1925 games): keep 256.
  Generation 2 (4.0.9: window v5c + gen01 + gen02 self-play) +29.8 vs generation 1 (SPRT H1, 1437 games).
  Generation 3 (4.1.0, first GPU-trained net, `scripts/nnue/train_gpu.py --layout plain`, mix.py: elite + v5c +
  gen01 + gen02 + 18M of the big run, endgames twice, 91M positions) +24.7 +-12 vs generation 2 (1592 games, H1).
  Generation 4 (4.1.1, first one from gpu_generations.py: 149M positions incl. 50.5M self-play of generation 3)
  +74.1 +-22.2 vs generation 3 (136-49-229, 414 games, H1) - fresh self-play of the current best is what counts.
  Generation 5 (4.1.2; mix: elite positions re-scored by our own search + gen3/gen4 self-play incl. 9.8M from the
  first cloud server) +34.0 +-14.4 vs generation 4 (240-149-544, 933 games, H1).
  Generation 6 (4.1.3; first net from uncertainty-focused self-play: 392M positions, mostly gen5 self-play of the
  Hetzner server with 3x nodes on uncertain positions) +32.2 +-11.4 vs generation 5 (423-277-882, 1582 games, H1;
  the first SPRT on the server with fastchess, 30 games at once: 28 minutes). The attempt before it (gen5 data
  without the focus, 128M) reached only +8 in 4000 games (undecided).
  A plain candidate from gen6 self-play (374M mix) was equal to gen6 (+1.6 +-6.7, 4000 games): the 768x256 net is
  saturated. Generation 7 (4.1.4) is the first king-bucket net (kb8: 8 own-king buckets, mirrored, factoriser,
  file v3, 3.2 MB): 463M-position mix with fresh gen6 self-play, 10 epochs, +22.1 +-9.3 vs generation 6
  (573-426-1315, 2314 games, H1); bench nps -2 % (Finny tables). The loop now runs with --layout kb8 --epochs 10.
- Search parameters (core/Tune.java) from SPSA, 1500 iterations x 8 games at 6+0.06 (4.0.9): +28.3 vs the old
  defaults (SPRT H1, 1058 games at 10+0.1). Biggest moves: nmpBaseMargin 150->100, nmpDepthMargin 15->24,
  aspDelta 15->10, rfpMargin 75->68, qsFutility 160->180. The pruning is more aggressive: CoreEngineTest's mate
  needs depth 10 instead of 8 (static tests must not veto an SPRT-confirmed gain).
  SPSA round 2 (1200 iterations from the round-1 values, gen3 net; state `E:\Chess-Data\tuning\spsa-411.json`):
  small moves (nmpBaseMargin 100->91, qsFutility 180->196, histPrune 1346->1244, lmrBase 86->93); verified with the
  gen4 net 660-626-2714 (+3.0 Elo, 4000 games, SPRT undecided) - not shipped: round 1 is at the optimum within noise.
  Why: Stockfish review of 30 server games found endgame moves lost 26 cp/move vs 14 in middlegames, and the
  training data had half the deep endgames of real games. Keep per-feature/per-bucket weight rows (offset 0).
- NNUE speed (2026-10-18, CPU-time bench, identical trees): accumulators are updated lazily (only when a node
  evaluates, -3..5 %); the int16 SIMD back end `nnue.VectorAccumulators` (Vector API) is ~5 % faster than the
  int32 loops C2 auto-vectorises (`ScalarAccumulators`). It needs `--add-modules jdk.incubator.vector` for javac
  AND java (pom, bot, harness snap.sh, cutechess.py, generations.py, Dockerfile have it); without the flag the
  engine silently uses the scalar back end. Plain int16 loops are 25 % SLOWER (C2 cannot vectorise the int16->int32
  widening of the output layer). `-XX:+UseCompactObjectHeaders`: no measurable effect (no objects in the hot path).
  Tried and reverted: TT move before move generation + lazy quiet scoring (+5 % nodes, no nps gain).
- Uncertainty head (4.1.2, `core.nnue.UncertaintyHead`, `core/nnue-unc.bin`): a 16-neuron head on the network's own
  SCReLU activations predicts |6000-node search - static eval| as a win probability. On 2M held-out self-play
  positions of generation 4 the 10 % it rates most uncertain hold 39 % of the large errors (> 0.10), simple rules
  (piece count, |eval|, p(1-p), a regression on them) 20 %, a linear head 29 % (`scripts/nnue/uncertainty_probe.py`).
  The file is bound to its network by CRC32: retrain it for every new network (`uncertainty_probe.py --export`,
  on that network's self-play), else the engine ignores it. Today it is only reported (`info string unc 0.042`
  before bestmove, `eval` prints it) for the bot and the play app; the search does not use it yet (bench unchanged).
  Self-play focus (DataGen `gen.uncNodes` / `gen.uncFork` / `gen.uncThreshold` 0.07, with `-Dcore.uncFile`): the
  13 % most uncertain positions (actual error 2.7x the rest, half of all large errors) get 3x nodes, 20 % of
  the games start from such positions; gpu_generations.py and burst.py turn it on when the best network has its
  head (`E:\Chess-Data
etsest-unc.bin`, retrained on every promotion).
  Tried, lost: uncertainty-aware LMR (`Tune.uncDepth/uncLo/uncHi`, off by default; head from the search's own
  activations, cached per key: no nps cost). The search meets far more 'unclear' nodes than self-play has, so
  the tree grows (+13..40 % nodes); SPSA (200 iterations, 3200 games) did not move from 8/14/65, and that
  setting lost 129-165-706 vs off (-12.5 Elo, 1000 games at 10+0.1). Time management is the untested use left.
- King buckets (kb8), second try: 114M positions, 4 epochs, row-wise Adam: -33 Elo vs 4.1.0 (78-109-139, 326 games)
  although its held-out loss was lower. `-Dcore.verifyNnue=true` (checks every 64th evaluation in the real search
  against a rebuild; `bench` reports the count) verified 30167 evaluations exact: the engine side is correct, the net
  needs more data (retry at several hundred million positions) or a cheaper layout.
  Third try (2026-10-20): 365M positions (gen4/gen5 self-play, 300M of it uncertainty-focused; 464M with endgames
  twice), 5 epochs, held-out loss still falling (0.00957 -> 0.00938, gen6 plain 0.00926): equal to generation 6
  (509-506-1363, 2378 games on the server, +0.4 +-8.8, stopped undecided). Data fixed the overfitting; it was
  trained on older data than gen6 used. Next: kb8 from fresh generation-6 self-play (the loop with --layout kb8).
- Tried, lost: node-effort time management (factor (160 - 100 * best-move node share) / 100): 82-134-314
  (-34 Elo, 530 games) - stopped too early; would need tuning (SPSA) before another try.
- Tried, no gain: continuation history of ply - 4 in quiet ordering (bench -16 % nodes, but 188-191-617 vs 4.1.0,
  996 games, ~0 Elo); pawn history (bench +27 % nodes, not played).
- Tried, no gain: pawn correction history (eval += learnt error per pawn structure): 81-83-294 vs 4.0.9 at
  10+0.1 (458 games, ~0 Elo); kept as refs/backup/local/exp-corrhist.
- Self-play data: `core.tools.DataGen` (`-Dgen.starts`, `-Dgen.syzygy`, `-Dgen.winScore/winPlies/drawPly/drawPlies`)
- Results (10+0.1, 1 thread): 40-0 vs 4.0.0, 40-0 vs 3.6.9, 52-6-2 vs Stockfish UCI_Elo 2600; 4 threads vs 1: +168 Elo
- Gap to Stockfish 19 (2026-10-18, 4.0.9, 1 thread, 64 MB, `E:\ChessEngines\harnesss_sf19.sh`): equal time 10+0.1
  0-93-7 (-576 +-160); SF19 at 1/8 time (1.25+0.0125) 1-160-39 (-377 +-56) -> about -580 Elo at equal time.
  Repeat both after each release to track the gap.
  4.1.5 (2026-10-20, generation 7 kb8; on the Hetzner EPYC with fastchess, 20 games at once, self-play at nice 19
  beside it; script in the session, engine wrapper /root/sf19/alieknek.sh): equal time 0-176-24 (-478 +-67, 200
  games); SF19 at 1/8 time 37-212-151 (-163 +-27, 400 games) -> +214 Elo closer at time odds than 4.0.9, about
  -400..-450 at equal time.
- Match harness: `E:\ChessEngines\harness\` (sprt.sh, match.sh, vs_sf.sh, smp.sh, snap.sh, elite_openings.epd);
  snapshots in `E:\ChessEngines\v5-dev\<name>\run.bat`
- Browser UI: `ChessEngineApplication` (Spring Boot) serves `static/` and `/ws/uci`, one core `Uci` per WebSocket
- UCI extras (all off by default, bench unchanged): `go searchmoves`, `Contempt` (cp a draw is worth less for the
  root side), `RootPenalty` ("e2e4:40 d2d4:15" or "none": those root moves are searched with the window shifted by
  the penalty, so they are only played when that much better; no exact TT store at a penalised root)
- Server releases: push branch vX.Y.Z, set ENGINE_REF/ENGINE_JAR in play/alieknek/Dockerfile, push play main, then
  `scripts/release/deploy_server.sh` (pause bot until no game, deploy.sh, bot on again in any case; ssh timeouts).
- Server deployment (play.juliussze.at, Occid3re/play repo): `alieknek` service with play window, switch and watch page
  (`src/main/resources/py/alieknek_service.py`, `watch/`)

## GPU training (`scripts/nnue`, PyTorch 2.4.1 + CUDA in `E:\Tools	orch-venv`, RTX 2080 Super)

- `core.tools.PackData` packs `fen;score;wdl` text into 32-byte records (`E:\Chess-Data\packed\*.bin`); the trainer
  keeps them in GPU memory (8 GB: ~150M positions).
- `train_gpu.py`: king-bucketed nets (format v3: 8 buckets by own king square, castled squares separate, board
  mirrored for kings on e-h) with a factoriser merged on export; same loss/quantisation as the Java trainer. The
  feature transformer is a dense 0/1 x weights GEMM in fp16 on the tensor cores (+ loss scaling): ~0.75M pos/s,
  twice PyTorch's EmbeddingBag (its backward sorts ~2M indices per batch).
- `parity.py`: float model vs the Java engine's `eval` of the exported file; must stay within quantisation
  (mean ~2-3 cp). Run it after every architecture change.
- C: is full (Syzygy 228 GB there): keep venvs, temp files and data on E:.

## Lichess knowledge (bot, `alieknek_service.py`)

- Game archive in the bot's data volume: `games.jsonl` (every game it plays: moves, its evals per move, opponent,
  ratings, clock) and `games-lichess.jsonl` (the account's earlier games, imported ONCE with a single public export
  request). Indexed at start: opponent stats (all / since the core engine, predictability = share of repeated
  opening positions where they played the same move again), opening experience per position (first 24 plies).
- In play: `RootPenalty` from the experience (40 cp per net loss against this opponent in this position, max 120;
  15 cp for a line that only drew against an opponent >= 100 below; 20 cp for a move scoring < 30 % in >= 3 games
  overall). Outbound challenges prefer opponents the bot beats more often than their rating predicts.
  Outbound pacing (4.1.3): one challenge at a time while idle - 20 s after a game (incoming first), 90 s after a
  challenge nobody took (cancelled after 45 s, so no second game), 300 s when no bot fits; time controls rotate
  (`OUTBOUND_TCS`, default bullet 2+1 / blitz 3+2 / rapid 10+5, weight 1 / (1 + games of the last 24 h));
  rating window +-200; online bots cached 5 min, ratings 30 min (a challenge usually costs one request, it used
  to cost four every 10 min). 429 still parks the loop for Retry-After.
  Rating-aware contempt exists (`BOT_CONTEMPT_PER_100`) but is OFF until a match shows it helps.
- `/alieknek/nnue.json` + `/alieknek/nnue.bin` on the public watch port: the engine's network for the app's
  network view (read from the jar once, ETag-revalidated); costs nothing during play.

## Training and tuning (`scripts/tuning`)

- `cutechess.py`: cutechess-cli wrapper (`run_match`, SPRT, result parsing) shared by the tools below
- `generations.py`: NNUE generations: DataGen self-play with the current best net -> train a child on a window of
  recent data + elite + endgames twice -> SPRT [0,5] child vs parent -> promote to `E:\Chess-Data\nets\best.bin`.
  Resumable (`E:\Chess-Data\generations\state.json`).
- `archive_starts.py`: self-play start positions from the bot's own Lichess games (archive JSONL from the server's
  `alieknek-data` volume; lost games x2), mixed with the endgame starts -> `generations.py --starts <file>`.
- `gpu_generations.py` (running since 2026-10-18 21:37, log `E:\Chess-Data\gpugen.log`, state
  `E:\Chess-Data\gpugen\state.json`): self-play never stops (DataGen below-normal priority, 20 threads); every
  50M new positions: shuffled mix of the 4 newest self-play files + elite + endgames twice -> GPU training ->
  parity -> SPRT [0,5] vs `E:\Chess-Data
etsest.bin` (4 cores) -> on H1 promote and restart self-play with
  it. Releases to the server stay manual. Stop: kill the python process and the DataGen java it started.
- `scripts/cloud/burst.py`: self-play on rented Linux servers (root ssh with the Hetzner key): `setup <ip>` copies
  the engine sources of a git ref + the best net, installs Temurin 27, the 3-4-5 Syzygy tables (sesse mirror, one
  file at a time - parallel downloads get 403) and the JNI library, starts DataGen on all threads; `sync` appends
  new positions to `E:\Chess-Data\cloud\`, `status`, `net all --net` (new network), `stop <ip>` (then cancel the
  server in Robot / console - billing runs until then). gpu_generations.py syncs every pass, counts the cloud
  positions towards `--positions`, mixes the files and switches the servers to a promoted net.
  2026-10-19: Hetzner auction EPYC 7502P (64 threads, 0.58 EUR/h incl. VAT, hourly, cancel immediately):
  4720 pos/s = 3.1x this PC (Ryzen 9 3900X, 20 threads: 1515 pos/s).
- `spsa.py`: Fishtest-style SPSA over `Tune.java` (all parameters at once, game pairs per iteration), resumable
  (`E:\Chess-Data\tuning\spsa-state.json`); `--verify` SPRTs the result against the defaults.
- No Stockfish-labelled data (user decision 2026-10-18): the engine learns only from its own games and search.
  The elite positions (Lichess elite games) are scored by our own search: `core.tools.Relabel` (6000 nodes, game
  results kept) -> `E:\Chess-Data\packed\elite2M-own.bin`.
- The former evolve*/genetic loops are gone: they picked the best of a few mutants after ~40 games each, i.e. noise.

## Key Decisions & Lessons (IMPORTANT — read before making changes)

1. **Static test suites can hide Elo regressions**. Always gate changes on self-play (SPRT or 40+ game matches at
   10+0.1), never on a test position score alone.
2. **Lichess bot needs auto-reconnect** — SSE stream drops randomly. Retry loop in `__main__`.
3. **In-game Lichess calls bypass the global pacing** (v4.0.1). `BOT_QPS=0.5` paced *every* non-stream call,
   including posting a move, so the bot never moved faster than one move per 2 s: 60+0 bullet was lost on
   time around move 30, blitz endgames ran at the 2 s floor. Moves, abort and resign now skip the limiter
   and the mutex and use a lean adapter (no status retries, no Retry-After sleep). Keep it that way.
4. **Time budget per move** (`SearchManager.allocate`, v4.0.1): moves-to-go 40 at the start shrinking to 16,
   optimum ≤ 20 % and maximum ≤ 40 % of the clock, under 10 s a twentieth plus most of the increment. The
   old `left/32`, max 45 % spent the clock early: in 3+2 only 20 s were left after move 40.
5. **Weekly play schedule** (`alieknek_service.py`, v4.0.2): `ALIEKNEK_HOURS` / `botctl hours` take weekday groups
   (`Mo-Fr 01:00-05:00; Sa-So 00:00-08:00`, `always`, `never`; days not named do not play; a window may
   cross midnight). The control API also takes `schedule` (seven lists of [start, end] minutes) and reports
   `schedule`, `windowEndsAt`, `nextWindowAt` – the play app's week editor builds on that.
   `windowEndsAt` follows a window across midnight into the next day's 00:00 start (v4.0.3) and is
   null for `always` – before that the app showed "bis 00:00 Uhr" for a bot that never stops.
6. **UCI must survive any `setoption`**: cutechess sends `setoption name Ponder value false`; parsing every unknown
   option as a tuning integer killed the engine loop (a 0-84 "SPRT" of disconnects). Unknown or non-numeric
   options are ignored; CoreEngineTest covers it.
7. **Lichess challenge errors carry a cooldown** (`'seconds': N`): the bot leaves that opponent alone for N s
   and ignores `challenge` events it sent itself (v4.0.4).
8. **Legacy AI removed (2026-10-18)**: the old `ai/`, `engine/`, `evaluation/`, `search/`, `tuning/` packages, its
   YAML tunings and HalfKP/PyTorch scripts are in git history (last at bf35e882). Old engine builds for matches
   stay in `E:\ChessEngines\` (v3.6.9, v4.0.0-classic, ...).

## Testing

- **Self-play**: CuteChess CLI at `"C:\Program Files (x86)\Cute Chess\cutechess-cli.exe"`
- **Engine slots**: E:\ChessEngines\ (v4.0.0-classic, v3.6.9, v3.9.1, v5-dev snapshots, etc.)
- **Stockfish**: E:\ChessEngines\stockfish_17.1\stockfish-windows-x86-64-avx2.exe
- **Bench**: `java -jar target/chess-engine-<version>-uci.jar bench` (node count changes with search or net)

## Bot Deployment

The production bot runs on the server (see the play repo's CLAUDE.md). Locally:

```bash
BOT_TC=blitz CHESSENGINE_THREADS=16 \
  /c/Python38/python.exe -u src/main/resources/py/lichess_bot.py
```

Python 3.8 required (Anaconda's 3.7 has broken pyopenssl). LICHESS_TOKEN must be set in env.
