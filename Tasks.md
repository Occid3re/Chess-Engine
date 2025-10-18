# BestMoveSearch – Evaluation Time Goal

### Goal

Speed up **BestMoveSearchTest** without changing any fixture moves or depths. A few failing tests are fine if total runtime drops.

### Rules

* Don’t edit `BestMoveFixture`.
* Tune only through `seed-tunings.yaml` or modify the code in AI.java if you find bugs related to seed-tunings.yaml values.
* Diagnostics and parallel search stay enabled.

### What to Improve

1. **Pruning**

    * Tune LMR, LMP, and NMP to cut more useless nodes.
    * Add futility/SEE thresholds in YAML.
2. **Move Ordering**

    * TT move always first if score close.
    * Knight/central capture tie‑break only within epsilon.
3. **Aspiration Windows**

    * Start narrow, widen on fail high/low.
4. **Evaluation**

    * Soften noisy modules in YAML (e.g. threat vs. activity) to avoid re‑searches.
5. **Performance Check**

    * Target runtime ≤ 3 min with diagnostics on.
    * Node count or locks down; helpers > 75 % busy.

### Measure

```bash
mvn -Djava.version=25 -Dmaven.compiler.release=25 -Dmaven.compiler.enablePreview=true \
    -DargLine="--enable-preview" -Dtest=BestMoveSearchTest test
```

### Accept

✅ Faster total time (≈ −30 %)
⚠️ ≤ 2 new fixture fails allowed
🧠 Keep YAML as single source of truth

### Log

Store decision traces under `logs/test-runs/<timestamp>/best-move-search/` and note any tuning change that improved speed.
