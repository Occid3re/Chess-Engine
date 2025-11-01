# BestMoveSearch – Accuracy Focus

### Goal

Improve **BestMoveSearchTest** pass rates while keeping the current ~3 min diagnostic runtime window.

**Current status:** Latest tuning passes hold the suite at **15 / 71** failures (baseline was 16). The worst offenders cluster around pawn-pressure situtations (`4k3/...`, `1k1r3r/...`, `r4rk1/...`) with cpLoss in the 3–12 range; diagnostics for each run continue to live in `target/surefire-reports/`.

### Guardrails

- Keep fixtures unchanged; treat `BestMoveFixtures` as read-only.
- Favor adjustments through `seed-tunings.yaml`, evaluation modules, or clearly targeted AI search fixes.
- Diagnostics, logging, and parallel search stay enabled so regression signals remain rich.

### Workstreams

1. **Failure Triage**
   - Aggregate failing FENs, classify by motif (tactical miss, evaluation skew, pruning overshoot).
   - Capture representative traces under `logs/test-runs/<timestamp>/best-move-search/`.
2. **Evaluation Tweaks**
   - Audit the blended score deltas for the high-loss positions.
   - Adjust weights (via tuning + evaluation modules) to close gaps where the engine picks the wrong move at the same depth.
3. **Search Stability**
   - Revisit aspiration, null-move, and reduction thresholds that cause the engine to bail out early on winning lines.
   - Add targeted safeguards (e.g., SEE/Futility tightening) only if they reduce false errors without adding runtime.
4. **Verification**
   - Re-run the suite (≥2 passes) to confirm failure counts drop while wall-clock stays in the existing window.
   - Record node counts and depth coverage summaries for before/after comparison.

### Measure

```bash
mvn -Djava.version=21 -Dmaven.compiler.release=21 -Dmaven.compiler.enablePreview=true \
    -DargLine="--enable-preview" -Dtest=BestMoveSearchTest test
```

### Accept

✅ Consistent runtime within ±5 % of the current baseline  
✅ Failure count reduced to ≤8 without introducing new flaky cases (currently 15 outstanding)  
⚠️ Note any trade-offs (e.g., increased evaluation draw bias) in the run logs

### Next Steps

- Target the pawn-pressure collapses first: raise the pawn-threat penalties for knights/rooks and/or add a light “no pawn shield” surcharge in `ThreatModule` so moves like `Nf3` (FEN `4k3/...`) get clipped before the engine commits. Profile cpLoss after each change.
- For `Bf5` in `1k1r3r/qppb2pp/...`, compare SEE and static eval — a handful of defenders still rate the capture as safe. Consider checking the SEE score inside `ThreatModule` before accepting hanging moves or wiring a one-ply SEE guard in search to veto that capture.
- The `Rxd2` miss (`8/2b5/...`) remains a high-magnitude regression. Instrument the diagnostics for that FEN and see whether the rook capture survives quiescence; if it does, tune the rook hanging penalty upward only for undefended squares (no friendly pawn coverage).
- Keep the full-suite runs flowing and log `cpLoss` deltas per position. The current run (see `target/surefire-reports/julius.game.chessengine.ai.BestMoveSearchTest.txt`) lists the 15 failures with their cpLoss budgets; use that as the regression ledger for the next pass.
