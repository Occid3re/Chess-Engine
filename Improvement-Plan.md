Improvement Plan: Evaluation & Legal Move Generation
===================================================

Purpose
-------
Raise evaluation throughput and legal-move generation speed without regressing playing strength or determinism. All ideas build on the current architecture documented in `Agents.md` (incremental `BitBoard` caches, watcher tables, bucketed move ordering, tuning hooks, and Syzygy integration). The plan favours instrumentation-first changes, keeps preview-flag compatibility, and leans on existing diagnostics (`BestMoveSearchTest`, `AITest_*`, PGN smoke suites) plus the rich sure-fire logs under `logs/test-runs/`.

Guardrails
----------
- Use **Java 21** + preview flags as the baseline matrix, but prefer **Java 25** on hosts where it is available (including this machine) when profiling or benchmarking; keep both pipelines green.
- Preserve determinism: reuse existing bucket ordering and tablebase behaviour, and rerun `BestMoveSearchTest` to confirm trace stability.
- Always capture before/after metrics (nodes/s, evaluation calls, generated-move counts) using the logging harnesses, or a dedicated profiler (JFR/JMC).
- Maintain compatibility with Windows + PowerShell workflows documented in `Agents.md`; avoid assuming a Linux-only toolchain.

Track A — Evaluation Pipeline Speed
-----------------------------------
1. **Baseline instrumentation**
   - Enable the existing logging hooks in `BestMoveSearchTest` and `AITest_QuiescenceAndTerminalInvariants` to record evaluation counts, quiescence hits, and time budget usage.
   - Record the outputs in a dated folder under `logs/test-runs/` for comparison.
   - ✅ (2025-10-16) Added opt-in evaluation profiling via `EvaluationPipeline.enableProfiling()` / `-Dchessengine.eval.profile=true`, capturing refresh calls, modules touched, and wall-clock. `ProfilingBaselineCaptureTest` writes snapshots to `logs/test-runs/<label>/`.
2. **Profile incremental modules**
   - Focus on `ActivityModule`, `PawnStructureModule`, and `computePositionalAdjustment` (noted in `Agents.md` as centralised orientation logic).
   - Use JFR with allocation profiling to ensure the cached helpers (`markRecalc`, `updateAttackCachesAfterChange`) are always hit after move application.
3. **Reduce redundant recomputation**
   - Audit evaluation entry points (especially `Score.refresh`) for redundant calls inside iterative deepening; cache the blended scores when the board hash matches.
   - Verify correctness by rerunning `ScoreTablebaseIntegrationTest` and `MateSearchTest`.
   - ✅ (2025-10-16) Introduced refresh fingerprinting in `Score.refresh/applyMove/undoMove` so unchanged hashes + clocks short-circuit without re-invoking the evaluation pipeline or Syzygy probes. Verified via `ScoreTablebaseIntegrationTest.refreshAvoidsRedundantTablebaseProbeWhenStateUnchanged`.
4. **Tune data structures**
   - Inspect fastutil `IntArrayList` usage in evaluation helpers; confirm they reuse buffers instead of allocating.
   - If adjustments are made, stress-test with `AITest_MateThreatDiagnostics` (covers evaluation-heavy lines) and log deltas.
5. **Regression protection**
   - Add or extend microbenchmarks (JUnit-perf or JMH harness) around `EvaluationPipeline.evaluate()` with typical mid-game positions stored under `src/test/resources`.
   - Gate merges on ±1% tolerance relative to the baseline logged earlier.

Track B — Legal Move Generation Speed
-------------------------------------
1. **Verify cache correctness**
   - Ensure `BitBoard` incremental maps stay in sync by tracing `markRecalc`/`updateAttackCachesAfterChange` in `Engine.performMove`.
   - Rerun `EngineConcurrencyTest` and `TranspositionTableZobristTest` to make sure concurrency helpers remain safe.
2. **Measure generator hotspots**
   - Attach a sampling profiler to `BestMoveSearchTest#diagnoseNe4SearchHotSpot` (see `Agents.md`) to capture per-depth move generation time.
   - Log branching factors and per-depth node budgets (already emitted) for before/after comparisons.
   - ✅ (2025-10-16) Added opt-in `Engine` instrumentation (`Engine.enableMoveGenerationProfiling()` / `snapshotMoveGenerationStats()`) to count generation calls, cache hits, total moves, and wall-clock without touching production hot paths. Guarded by `-Dchessengine.movegen.profile=true` for CLI usage.
   - ✅ (2025-10-16) `BestMoveSearchTest` now prints aggregated profiling totals at suite teardown when the move/eval profilers are enabled, making it easy to grab counts from diagnostic runs.
3. **Evaluate watcher-table impact**
   - Toggle `-Dchessengine.activity.linearScanFallback=true` (legacy scan) to contrast watcher-table performance during profiling.
   - Document findings; if watchers underperform in some scenarios, introduce targeted optimisations (e.g., reducing cache invalidation scope).
4. **Explore SIMD-friendly bit scans**
   - Review `MoveHelper` and `PawnMoveTables` for opportunities to use Java 21+ vector intrinsics or faster `Long.numberOfTrailingZeros` batching.
   - Validate move legality with `EngineSimulationTest` and `UciProtocolTest`, ensuring no illegal move leakage.
5. **Reassess move bucketing**
   - The current deterministic order (TT → promotions → SEE captures → killers → quiet → bad captures) should remain, but profile bucket transitions for overhead.
   - If optimising, keep the tie-breaker on move id to preserve determinism; rerun `AITest_MoveOrderingBuckets` to confirm behaviour.

Validation Checklist
--------------------
- `mvn -Djava.version=21 -Dmaven.compiler.release=21 -Dmaven.compiler.enablePreview=true -DargLine="--enable-preview" test`
- Syzygy-aware suites (when native bridge available):
  - `SyzygyMockRegressionTest`
  - `SyzygyRealIntegrationTest`
  - `BestMoveSearchTest` (expect diagnostic failures but check counts/logs)
- Regression-specific tests:
  - `TablesTest.decodeRecommendedMoveReturnsEmptyWhenPayloadZeroed`
  - `ScoreEvaluationTest.backwardPawnIsPenalized`
  - `AITest_LazySmpSnapshot` (ensures concurrency buffers are stable)

Deliverables
------------
1. Profiling reports (JFR/async-profiler) annotated with hotspots and proposed code changes.
2. Before/after log bundles stored under `logs/test-runs/<yyyy-mm-dd>/`.
3. Optional JMH or JUnit-based microbenchmarks committed under `scripts/tests/` with clear instructions in `Agents.md` if new tooling is required.
