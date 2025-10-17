# BestMoveSearch Optimisation Roadmap

## Guiding Principles
- Optimise the actual engine search loop; the diagnostic harness must remain a faithful mirror of production behaviour.
- Measure first, change second. Every optimisation must demonstrate tangible search-time or accuracy gains against the baseline.
- Maintain deterministic outputs and existing mate/score conventions; protect against evaluation regressions with focused tests.

## Phase 0 – Baseline & Instrumentation (Day 0-1) ✅
1. ✅ Reproduced the depth-8 BestMoveSearch scenario (single-thread baseline ≈3.0 s).
2. ✅ Captured telemetry (per-depth nodes, TT stats, JFR profile of hotspots).
3. ✅ Established baseline target (<2 s for depth 8) against the recorded 3 s single-thread run.
4. ✅ Archived diagnostics and JFR under `logs/test-runs/20251017_172542/baseline/`.

## Phase 1 – Concurrency & Scheduling (Day 2-4)
1. ✅ Auto-detect optimal `searchThreads`/`lazySmpThreads`/`rootParallelLimit` when no overrides are provided (SearchConcurrencyPlanner in place).
2. ⏳ Audit thread start-up and coordination:
   - Ensure helper threads are spawned once and reused.
   - Remove unnecessary synchronisation (e.g., lock contention in heuristics merge).
3. ⏳ Benchmark multi-core scaling; target ≥1.8× speed-up vs. single-thread baseline on 8+ logical cores.
4. ⏳ Extend regression tests to cover multi-thread configurations (BestMoveSearch + AITest_MoveOrderingBuckets).

> Current status: planner integrated; deterministic tests updated. Need multi-thread benchmark run and coordination profiling once Syzygy/TT tests pass.

## Phase 2 – Root Move Ordering & Iterative Deepening (Day 5-7)
1. Analyse root ordering statistics (TT, killers, history scores) for the slow scenario.
2. Optimise:
   - Prioritise TT move propagation earlier in ordering.
   - Introduce lightweight history heuristics reset between root depth iterations.
   - Revisit aspiration window sizing to avoid repeated fail-high/fail-low cascades.
3. Add targeted micro-benchmarks for ordering changes (per-depth node counts, cutoff rates).

## Phase 3 – Pruning & Reduction Policies (Day 8-11)
1. Re-evaluate null-move pruning depth/material thresholds for late endgames like the provided FEN.
2. Tune LMR/LMP parameters using staged experiments (single-position, suite, then auto tuner).
3. Validate against regression suites (MateInThreeToSeven, AvoidRepetitionDraw) to ensure tactical safety.

## Phase 4 – Evaluation Fast Path (Day 12-14)
1. Profile evaluation hotspot frequency in depth-8 run; cache or precompute terms that dominate (>10% CPU).
2. Investigate incremental update opportunities for king safety / pawn structure specific to sparse endgames.
3. Add evaluation microbenchmarks to guard against future regressions.

## Phase 5 – Validation & Rollout (Day 15-16)
1. Re-run full BestMoveSearch suite, MateSearch, PGN smoke tests, and targeted multi-thread stress runs.
2. Compare new telemetry vs. Phase 0 baseline; ensure:
   - Depth-8 scenario < 2 s (or equivalent success at lower depth).
   - No increase in regression failures.
3. Document changes in `Agents.md` (testing guidance) and create release notes summarising key wins.

## Ongoing Workstreams
- Automate nightly benchmark of representative FENs across depth targets.
- Maintain a tuning backlog for search/evaluation experiments informed by telemetry.
- Periodically review `seed-tunings.yaml` alignment with engine changes to avoid stale parameters.

## Next Steps
1. Stabilise test suite with the new planner defaults:
   - Fix `UciSyzygyFlowTest` to assert TB hits without timing out (use deterministic single-thread tuning and mock result injection).
   - Confirm transposition-table capacity tests reflect live weight ratios.
2. Capture multi-threaded BestMoveSearch telemetry (depth 8) and compare against the 3 s baseline.
3. Profile coordination locks with ≥16 helper threads; identify write-lock hot spots to trim in Phase 1 Step 2.
4. Once concurrency improvements are validated, proceed to Phase 2 root-move ordering analysis.
