# BestMoveSearch – Accuracy Focus

### Goal

Improve **BestMoveSearchTest** pass rates without extending the current ~60 s diagnostic runtime budget.

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
✅ Failure count reduced to ≤8 without introducing new flaky cases  
⚠️ Note any trade-offs (e.g., increased evaluation draw bias) in the run logs

### Next Steps

- Rebalance queen-safety heuristics so gambits like `Qxb5` in `4k3/1bp1bp1p/...` lose priority versus structure-preserving moves (`a3`, `f3`, `a4`). With the new back-rank guard knobs, sweep cover/attack values in small steps while confirming the `3rk2r/...` fixture stays under the 5.5 cp margin.
- Increase endgame king activity bonuses cautiously until `Ke6` overtakes `Re6` in `3B4/3nrk1p/...` without inflating runtime; track cpLoss as `activity.endgamemobilityking` and `activity.endgamecenterking` shift.
- Keep logging fresh runs under `logs/test-runs/<timestamp>/best-move-search/`, capturing cpLoss + node deltas for the two remaining failing FENs (`4k3/...`, `3B4/...`) to guide each tuning pass.
