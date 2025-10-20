# BestMoveSearch – Accuracy Focus

### Goal

Improve **BestMoveSearchTest** pass rates while keeping runtime within the 30 s target under the mandated Java 25 + Syzygy configuration.

**Current status (2025‑10‑20):** Depth‑4 runs previously completed in **~31–33 s** with the required flags on the reference Windows host (24 / 86 failures). Under WSL with the Linux toolchain (`JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64`) the latest depth‑6 run now finishes in **265.1 s** with **24 / 86** failures after introducing root futility gating (command below). The prior depth‑6 baseline was **328.2 s** (24 / 86). Runtime captures live in `target/surefire-reports/`.

```
mvn -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true \
    -DargLine="--enable-preview -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA -XX:ActiveProcessorCount=24" \
    -Dchessengine.syzygy.nativeLibrary=/mnt/c/Development/Chess-Engine/target/classes/natives/linux-x86_64/libJSyzygy.so \
    -Dchessengine.syzygy.paths=/mnt/c/Syzygy -Dchessengine.tt.mb=1024 -Dchessengine.searchThreads=1 \
    -Dtest=BestMoveSearchTest test
```

Root early-stop now requires a **200 cp** improvement over baseline plus a 50 cp cushion over the runner-up before pruning the remaining root moves. Additional root futility / despair guards (all now tunable via `seed-tunings.yaml`) skip hopeless quiet moves once the leader is clearly ahead (≥300 cp) or the evaluation exceeds ±5 pawns, trimming depth‑6 fan-out without touching fixtures. Diagnostics remain in `target/surefire-reports/` for each run.

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

- Profile high-cost fixtures (e.g., `Kg2/Kg3/Kg4` cluster) to confirm the futility guard isn't masking tactical saves before we consider tightening the 120 cp margin or 300 cp lead thresholds.
- Re-run the suite after any heuristic tweaks to track whether we can claw back the baseline 24/86 failure count while keeping the new runtime gains.
- Continue logging `cpLoss` deltas per position; use `target/surefire-reports/julius.game.chessengine.ai.BestMoveSearchTest.txt` as the working ledger for regressions.
