# Agents.md

## Purpose
This file tells humans and automation agents how to run tests **consistently** in this project.  
Defaults are **simple and safe**. Agents **may** enable parallel search when resources allow (useful for `BestMoveSearchTest`).

---

## Testing Policy (Default)
All tests must use **Java 21** with **preview features enabled**.

### Default test command
```bash
mvn -Djava.version=21     -Dmaven.compiler.release=21     -Dmaven.compiler.enablePreview=true     -DargLine="--enable-preview"     test
```

### Common variants
- **Only AI tests**
  ```bash
  mvn -Djava.version=21       -Dmaven.compiler.release=21       -Dmaven.compiler.enablePreview=true       -DargLine="--enable-preview"       -Dtest=AITest*,AITest_*       test
  ```
- **PGN module smoke tests**
  ```bash
  mvn -Djava.version=21       -Dmaven.compiler.release=21       -Dmaven.compiler.enablePreview=true       -DargLine="--enable-preview"       -Dtest=PgnParserTest,OpeningPgnReaderTest       test
  ```
- **Single class / method**
  ```bash
  # Class
  mvn -Djava.version=21 -Dmaven.compiler.release=21 -Dmaven.compiler.enablePreview=true       -DargLine="--enable-preview"       -Dtest=BestMoveSearchTest       test

  # Method
  mvn -Djava.version=21 -Dmaven.compiler.release=21 -Dmaven.compiler.enablePreview=true       -DargLine="--enable-preview"       -Dtest=BestMoveSearchTest#shouldFindSameBestMoveUnderParallelLoad       test
  ```

> Keep `--enable-preview` in **`argLine`** so the test JVM receives it.

---

## Optional: Parallel Search (Agent Guidance)
Some suites (notably **`BestMoveSearchTest`**) benefit from parallel search to exercise concurrency paths.  
Agents **may** enable and scale parallelism **based on available hardware**.

### Optional JVM & engine flags
Append these to `argLine` (and/or as `-D` props) **only if resources allow**:

```text
# JVM (optional)
-Xms8g -Xmx8g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=20
-XX:+AlwaysPreTouch
-XX:+UseNUMA
-XX:ActiveProcessorCount={P}

# Engine (optional)
-Dchessengine.tt.mb={TT}
-Dchessengine.searchThreads={S}
-Dchessengine.lazySmpThreads={L}
-Dchessengine.rootParallelLimit={R}
```

#### Autoscaling heuristics (suggested)
- `P` = available **logical** CPUs  
- `S = max(2, floor(0.67 * P))`  (search threads)  
- `L = max(1, floor(S / 2))`     (lazy SMP threads)  
- `R = max(2, 3 * P)`            (root parallel limit)  
- `TT = 256` (MB) by default; increase only if RAM is plentiful.

> If any of `{P,S,L,R,TT}` are explicitly provided, **do not override** them.

### Example (only when resources allow)
```bash
mvn -Djava.version=21     -Dmaven.compiler.release=21     -Dmaven.compiler.enablePreview=true     -DargLine="--enable-preview       -Xms8g -Xmx8g       -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA       -XX:ActiveProcessorCount={P}       -Dchessengine.tt.mb={TT}       -Dchessengine.searchThreads={S}       -Dchessengine.lazySmpThreads={L}       -Dchessengine.rootParallelLimit={R}"     test
```

---

## CPU discovery hints (for agents)
- **Linux:** `P=$(nproc)`
- **macOS:** `P=$(sysctl -n hw.logicalcpu)`
- **Windows (PowerShell):** `$P=$env:NUMBER_OF_PROCESSORS`

Agents should compute `S, L, R, TT` via the heuristics above and substitute into the example command.

---

## Conventions
- Prefer the **default command** unless a test explicitly benefits from concurrency.
- Parallel flags are **optional** and intended for stress-testing and stability checks (e.g., `BestMoveSearchTest`).
- Keep flags in `argLine` so they apply to the **Surefire test JVM**.

### Test instrumentation & WebSocket coverage
* `TestLoggingExtension` is now registered on the new `UciWebSocketHandlerTest`. The extension logs **start**, **end**, **success**,
  and **failure** events for every method, plus a full thread dump on errors. Inspect the logs in the Maven output or in
  `target/surefire-reports/*.txt` to understand how the engine behaved during the test run.
* To focus on the WebSocket lifecycle tests (useful when debugging frontend connectivity), run:
  ```bash
  mvn -Djava.version=21 -Dmaven.compiler.release=21 -Dmaven.compiler.enablePreview=true \
      -DargLine="--enable-preview" -Dtest=UciWebSocketHandlerTest test
  ```
  This suite covers handler registration, command routing, and error handling, giving future agents concrete logs of the
  WebSocket handshake and teardown paths.

### Additional notes
* `MateSearchTest` keeps the shorter time budgets (50–500ms) for diagnostics only. The suite now requires the engine to
  succeed when it receives the largest configured per-move budget (currently 10s), so occasional misses at lower limits are
  acceptable.
* Evaluation weights & tuning: All evaluation weights are wired through the tuning module using seed-tunings.yaml. When introducing new weights:
  Define them in the corresponding evaluation module (where the feature is computed).
  Add the same weights (with sensible defaults) to seed-tunings.yaml.
  Expose and apply them via the tuning module so they are loaded and propagated into the evaluation at runtime.
  Keep `scripts/auto_tuning_loop.py`'s `PARAM_MUTATION_HINTS` in sync with `seed-tunings.yaml`; every numeric entry now has soft bounds and mutation steps (including aspiration window, null-move, TT weighting, and SEE clamp controls).
* Auto tuning loop: `scripts/auto_tuning_loop.py` now reads the numeric bounds from `ParamId.java` and applies search-specific heuristics (including snapping to sentinel values such as `-1`/`0`). When new numeric search parameters are added, make sure their metadata in `ParamId` is updated so the tuner can scale mutations and clamp values correctly.
* Search pruning knobs exposed for tuning: `search.maxCheckExtensionStreak`, `search.seePruneNearRootPly`, and `search.historyReductionMax` are now part of the seed tuning set with soft caps in `auto_tuning_loop.py`. Keep their metadata in sync when adjusting forcing-line or LMR heuristics.
* Full-suite runs currently fail in `BestMoveSearchTest` due to long-horizon move selection mismatches. Use the PGN smoke tests above when focusing on PGN changes, or investigate the AI regressions separately before expecting a green build.
* `BestMoveSearchTest` now mirrors the diagnostic harness from `AITest_MateThreatDiagnostics`. Each position prints an in-depth iterative-deepening trace, principal variation, and transposition-table probe summary, with aggregated JSON/TXT artifacts in `target/surefire-reports/best-move-search-*`. Expect a long console log and double-digit known assertion failures (~10–30; see the latest counts in `logs/test-runs/`); the goal is visibility, not a green suite.
* `BestMoveSearchTest#diagnoseNe4SearchHotSpot` isolates the slow `Ne4` fixture (`3rk2r/1bqpbppp/p1n1p3/1p2P3/5Bn1/2NQ1N2/PPP1BPPP/R2R2K1 w k - 5 14`). Run it with
  ```bash
  mvn -Djava.version=21 -Dmaven.compiler.release=21 -Dmaven.compiler.enablePreview=true \
      -DargLine="--enable-preview" \
      -Dtest=BestMoveSearchTest#diagnoseNe4SearchHotSpot test
  ```
  The test takes ~2 minutes on the reference hardware and prints:
  - A search summary (result, depth reached, nodes, null-move usage, environment).
  - **Depth aggregates** (attempt count, average root branching factor, total nodes/time).
  - **Root move hot spots** showing which root candidates dominate the node budget.
  - **Per-depth leaderboards** of the most expensive moves, including alpha/beta windows and evaluation sources.
  Use these logs to spot missing beta cut-offs, ineffective move ordering, or null-move pruning gaps before adjusting pruning heuristics.
* `BitBoard` now caches per-piece attack contributions incrementally. When touching move application code, call the existing helpers (`markRecalc`, `updateAttackCachesAfterChange`, etc.) so both the caches and the aggregated maps stay in sync without forcing full recomputation.
* `ActivityModule` precomputes bishop/rook watcher tables so slider updates can skip full-board scans. Pass `-Dchessengine.activity.linearScanFallback=true` to restore the legacy scan when debugging or if the watcher tables need to be bypassed.
* Move ordering now uses per-thread bucketed buffers (TT → promotions → SEE-sorted captures → killers → quiet → bad captures) instead of a global `Arrays.sort`. Buckets reuse IntArrayList storage, tie-break on the move id to remain deterministic, and cut the `BestMoveSearchTest` runtime on the reference container from ~3m36s to ~3m14s.
* Legal move profiling: set `-Dchessengine.movegen.profile=true` (or call `Engine.enableMoveGenerationProfiling()`) to accumulate generation counts, cache hits, total moves, and wall-clock. Inspect via `Engine.snapshotMoveGenerationStats()` and reset with `Engine.resetMoveGenerationProfiling()` when comparing runs.
* Evaluation profiling: set `-Dchessengine.eval.profile=true` (or call `EvaluationPipeline.enableProfiling()`) to track refresh counts, modules evaluated, and aggregate time. Use `EvaluationPipeline.snapshotProfiling()` for stats and reset with `EvaluationPipeline.resetProfiling()` before/after scenarios.

### AI search & evaluation notes
* Quiescence delta pruning now compares against the alpha window captured before the stand-pat update. Earlier builds raised `alpha` first, which meant `standPat + Δ < alpha` never triggered and the pruning shortcut was effectively disabled.
* Static evaluation adds a lightweight knight placement adjustment (central bonuses) plus a tempo tie-breaker on top of the pipeline score. Future positional tweaks should extend `computePositionalAdjustment` so the orientation logic stays centralised.
* Aspiration windows now derive their initial span from recent score volatility and decay as searches stabilise. Repeated fail-high/low streaks widen the relevant side of the window more aggressively before conceding to a full-window retry, reducing the number of depth≥3 re-searches that fall back immediately to `(-∞, +∞)`.

### 2025-10-07 Time management + evaluation notes
* The engine now relies on `TimeManager` (under `ai/time/`) for soft/hard deadlines. Update UCI tests to expect the bullet promotion allocation of **250ms** (the Java planner now mirrors the conservative reserves used in `src/main/resources/py/lichess_bot.py`). Document any future tuning against that Python helper here so the two stay aligned.
* `ScoreEvaluationTest.backwardPawnIsPenalized` now validates the pawn-structure view directly. The overall blended score remains neutral with current tuning, so assert against `PawnStructureModule.backwardPawnPenalty()` instead of a blended delta.
### 2025-10-16 Syzygy bridge verification
* Current host `JAVA_HOME` points at `C:/Users/juliu/.jdks/openjdk-25`. Default Maven invocations therefore compile against Java 25, even though earlier sections document Java 21 for agents without that toolchain.
* PowerShell tip: run the real Syzygy integration suite by delegating to `cmd.exe` so the `-D` flags survive PowerShell parsing, e.g.
  ```powershell
  cmd.exe /c ".\mvnw.cmd -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true -DargLine=--enable-preview -Dchessengine.syzygy.nativeLibrary=C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll -Dchessengine.syzygy.paths=C:\Syzygy -Dtest=SyzygyRealIntegrationTest test"
  ```
* After correcting DTZ sentinel handling (Oct 2025), the command above now completes cleanly; expect roughly 40s wall-clock on this host.

* Preconditions: real runs require the native bridge at `C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll` and the Syzygy tables under `C:\Syzygy` (with the 3-4-5 and 6-piece folders). Run tests with preview flags plus:
  ```bash
  .\mvnw.cmd -Djava.version=21 -Dmaven.compiler.release=21 -Dmaven.compiler.enablePreview=true -DargLine=--enable-preview -Dtest=SyzygyWinRegressionTest -Dchessengine.syzygy.nativeLibrary=C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll -Dchessengine.syzygy.paths=C:\Syzygy test
  ```
* What worked: `SyzygyWinRegressionTest` passed and the logs confirmed the bridge loaded `JSyzygy.dll`, expanded the path into `C:\Syzygy\3-4-5-*` and `C:\Syzygy\6-*`, and reported `supportedPieces=6` (the current TB set) even with `maxPieces=7`.
* WDL/DTZ check: probing `3k4/4p3/8/2K5/8/3BN3/8/8 w - - 0 1` through a temporary runner returned `wdl=WIN`, `dtz=7`, no `dtm`, and a move recommendation of `c5c6`. This matches the adjustment rules in `Tables.probe` (cursed/blessed handling stays untouched when WDL and DTZ agree).
* Edge cases: the JVM emits `System::load` native-access warnings; suppress with `--enable-native-access=ALL-UNNAMED` if a future run locks this down. Reloading different directories in the same process is blocked (`SyzygyBridge.load` keeps `tbLargest`); restart the JVM to swap TB roots.
* Fallback: when the properties/env vars are missing, `TestSyzygySupport.isSyzygyConfigured()` stays `false` so tablebase tests auto-skip and `SyzygyTablebaseService` falls back to its no-op client, keeping CI/dev flows safe without TBs.
* Regression coverage: `TablesTest.decodeRecommendedMoveReturnsEmptyWhenPayloadZeroed` asserts that zeroed DTZ payloads keep returning `Optional.empty()` recommendations.
* Real vs mock coverage: use `SyzygyRealIntegrationTest` for native-backed verification and `SyzygyMockRegressionTest` for CI-friendly checks. Both expect Java preview flags; the real test also needs the properties above. Example:
  ```bash
  .\mvnw.cmd -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true -DargLine=--enable-preview \
      -Dchessengine.syzygy.nativeLibrary=C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll \
      -Dchessengine.syzygy.paths=C:\Syzygy -Dtest=SyzygyRealIntegrationTest test
  ```
  Mock path:
  ```bash
  .\mvnw.cmd -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true -DargLine=--enable-preview -Dtest=SyzygyMockRegressionTest test
  ```
* `BestMoveSearchTest` now assumes the same Syzygy properties when present (it auto-skips if they are missing) and expects the updated move list (`Nxg4` is now valid for `3r2k1/...`). Run it with the native properties for full diagnostics; omit them to confirm the skip:
  ```bash
  .\mvnw.cmd -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true -DargLine=--enable-preview \
      -Dchessengine.syzygy.nativeLibrary=C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll \
      -Dchessengine.syzygy.paths=C:\Syzygy -Dtest=BestMoveSearchTest test
  ```
* Linux/WSL workflow: CMake’s configure step fails with `Operation not permitted` when the repo lives on a DrvFS mount (`/mnt/c/...`). When running inside WSL, copy the workspace to an ext4 volume (for example `rsync … /tmp/chess-engine-run/`), export `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64`, and invoke Maven with `-Dmaven.repo.local=.m2/repository` so cached dependencies stay within that scratch area. After tests finish, copy `target/surefire-reports` back into the main repo (we stage them under `logs/test-runs/<timestamp>/`).
* Linux tip: the Windows `mvnw` script keeps CRLF endings, so running `bash mvnw …` inside WSL trips on `$'\r'`. Prefer the system Maven (`mvn …`) or run the wrapper from PowerShell/cmd.
* Diagnostic runs: `BestMoveSearchTest` is still expected to fail (goal = rich logs). Recent Java 25 runs produced 10–30 assertion failures (13 on 2025-10-16); see `logs/test-runs/<timestamp>/julius.game.chessengine.ai.BestMoveSearchTest.txt` for the detailed traces.
