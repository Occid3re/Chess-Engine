# BestMoveSearch – Accuracy Focus

### Goal

Improve **BestMoveSearchTest** pass rates while keeping runtime within the 30 s target under the mandated Java 25 + Syzygy configuration.

**Current status (2025‑10‑20):** Depth‑4 runs previously completed in **~31–33 s** with the required flags, returning **24 / 86** failures. Root early-stop now kicks in once the leader is ≥300 cp ahead of the baseline and 150 cp clear of the runner-up; this should trim root fan-out without touching fixtures. Fresh timings still pending while the local toolchain is wired up (`JAVA_HOME` is required before Maven can finish the JNI configure step). Diagnostics remain in `target/surefire-reports/` for each run.

Baseline commands & stats:

```bash
# Full static diagnostics (Java 25, preview features)
mvn -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true \
    -DargLine="--enable-preview" -Dtest=BestMoveEvaluationDiagnosticsTest test

# Full BestMoveSearchTest with required runtime flags
mvn -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true \
    -DargLine="--enable-preview -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA -XX:ActiveProcessorCount=24" \
    -Dchessengine.syzygy.nativeLibrary="C:\\Development\\Chess-Engine\\target\\classes\\natives\\win-x86_64\\Release\\JSyzygy.dll" \
    -Dchessengine.syzygy.paths="C:\\Syzygy" -Dchessengine.tt.mb=1024 -Dchessengine.searchThreads=1 \
    -Dtest=BestMoveSearchTest test

# Representative runtime result (2025-10-20)
#   - Wall-clock: 31.7 s
#   - Failures: 24 / 86
#   - Nodes: see target/surefire-reports/julius.game.chessengine.ai.BestMoveSearchTest.txt
```

### Guardrails

- Keep fixtures unchanged; treat `BestMoveFixtures` as read-only.
- Favor adjustments through `seed-tunings.yaml`, evaluation modules, or clearly targeted AI search fixes.
- Diagnostics, logging, and parallel search stay enabled so regression signals remain rich.

### Workstreams

1. **Failure Triage**
   - Aggregate failing FENs, classify by motif (tactical miss, evaluation skew, pruning overshoot).
   - Capture representative traces under `logs/test-runs/<timestamp>/best-move-search/`.
2. **Evaluation Tweaks**
   - Audit blended vs static deltas for high-loss positions.
   - Adjust weights (via `seed-tunings.yaml` + evaluation modules) to address queen/rook tactical slips without inflating cp-loss elsewhere.
3. **Search Stability & Runtime**
   - Revisit aspiration, null-move, and reduction thresholds that cause the engine to waste time or miss shallow wins.
   - Use targeted heuristics (e.g., SEE/futility tightening, early-stop margin tuning) if they lower wall-clock while preserving depth coverage.
4. **Verification**
   - Re-run the suite (≥2 passes) to confirm failure counts drop and wall-clock ≤30 s.
   - Record node counts and depth coverage summaries for before/after comparison.

### Measure

```bash
mvn -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true \
    -DargLine="--enable-preview -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA -XX:ActiveProcessorCount=24" \
    -Dchessengine.syzygy.nativeLibrary="C:\\Development\\Chess-Engine\\target\\classes\\natives\\win-x86_64\\Release\\JSyzygy.dll" \
    -Dchessengine.syzygy.paths="C:\\Syzygy" -Dchessengine.tt.mb=1024 -Dchessengine.searchThreads=1 \
    -Dtest=BestMoveSearchTest test
```

### Accept

✅ Consistent runtime within ±5 % of the current baseline  
✅ Failure count reduced to ≤8 without introducing new flaky cases (currently 24 outstanding)  
⚠️ Note any trade-offs (e.g., increased evaluation draw bias, higher queen-safety penalties) in the run logs

### Next Steps

- Capture a new BestMoveSearchTest run (same flags) once `JAVA_HOME` is exported so the JNI build step succeeds; record wall-clock, failure count, and root fan-out deltas after the 300 cp early-stop change.
- If runtime still hovers above 30 s, experiment with narrowing the runner-up gap (currently 50 % of the margin) or mild aspiration tightening, and document any regression risk in the logs.
- Continue logging `cpLoss` deltas per position; use `target/surefire-reports/julius.game.chessengine.ai.BestMoveSearchTest.txt` as the working ledger for regressions.
