# Depth-8 Baseline Snapshot

- Command: `mvn -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true -DargLine="--enable-preview" -Dtest=BestMoveSearchTest test`
- Position: `3R4/8/P4pk1/rP4p1/8/8/1K6/8 w - - 3 65`
- Search environment: 24 logical cores, `searchThreads=1`, `lazySmpThreads=1`, `rootParallelLimit=24`, transposition table 64 MB.
- Depth target: 8 plies (iterative deepening reached depth 8).
- Observed metrics:
  - Wall-clock: ≈3.0 s (`search duration: 3033 ms`).
  - Nodes searched: ~4.8×10^5.
  - Null-move prunes: 8.4×10^3.
  - TT coverage: depth 7 entries for the expected move set.
- Artifacts:
  - `BestMoveSearchTest.xml` contains the full diagnostic dump.
  - `bestmove-depth8.jfr` captures a Java Flight Recorder profile of the same run.

These figures establish the baseline for subsequent optimisation work.
