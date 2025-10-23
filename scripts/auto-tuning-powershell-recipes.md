# Auto Tuning PowerShell Recipes


---

# Neural‑network ( `learning.*` ) genetic manipulation

This section shows **isolation strategies and CLI recipes** for evolving the small neural head you exposed under `numericParameters.learning.*`, while keeping the classical evaluation frozen (all other modules set to `0.0`, `LearningEvaluationModule` at `1.0`).

> Why isolate?  
> The learning weights are tiny (≈ −0.05 … +0.06) and very sensitive. Mutating them together with big CP‑scale params can drown their signal. Use *allow/deny* filters plus smaller mutation fractions and cooler temperatures.

## Reference NN‑only seed (example)

```yaml
population:
  - name: beast
    evaluation:
      modules:
        MaterialModule:   { midgame: 0.0, endgame: 0.0 }
        PawnStructureModule:{ midgame: 0.0, endgame: 0.0 }
        ActivityModule:   { midgame: 0.0, endgame: 0.0 }
        KingSafetyModule: { midgame: 0.0, endgame: 0.0 }
        ThreatModule:     { midgame: 0.0, endgame: 0.0 }
        LearningEvaluationModule: { midgame: 1.0, endgame: 1.0 }
    numericParameters:
      # … huge block of learning.layer{0,1}.* weights/biases …
      # (see your provided config)
```

---

## Regex filters you’ll actually use

- **All learning weights & biases**  
  `--allow-pattern "^(learning\.)"`

- **Only biases** (all layers)  
  `--allow-pattern "^(learning\..*\.bias$)"`

- **Only weights (no biases)**  
  `--allow-pattern "^(learning\..*\.weight\d+)$"`

- **Layer‑scoped:**
    - Layer 0 only → `--allow-pattern "^(learning\\.layer0\\.)"`
    - Layer 1 only → `--allow-pattern "^(learning\\.layer1\\.)"`

- **Neuron subset (layer 0, neurons 0–2):**  
  `--allow-pattern "^(learning\\.layer0\\.neuron(0|1|2)\\.)"`

- **Exclude the classical eval & search knobs while you learn the head:**  
  `--deny-pattern "^(evaluation\\.|material\\.|pawnstructure\\.|activity\\.|development\\.|kingsafety\\.|threat\\.|moveordering\\.|search\\.)"`

> Tip: Start with *biases only*, then add layer1 weights, then gradually reveal layer0 weights per‑neuron group.

---

## Safe mutation knobs for tiny weights

Because `learning.*` values are small, keep exploration *cool and sparse*:

- `--mut-frac 0.01 --mut-frac-min 0.005 --mut-frac-max 0.02`
- `--temp-start 0.12 --temp-min 0.03 --temp-decay 18`
- `--accept-temp 0.03` (anneal rarely)
- `--freeze-after 40` (give each weight many tries before freezing)
- Prioritize correctness/quality over speed during learning:  
  `--duration-bonus-threshold 0.00 --duration-weight 0.5 --max-failure-regress 0 --max-error-regress 0`
- Plateau handling:  
  `--noimp-reheat 20 --reheat-factor 1.3`

If updates feel too jumpy, **halve** `--temp-start` or `--mut-frac`. If learning stagnates, temporarily raise `--mut-frac-max` to `0.03` and reheat sooner (e.g., `--noimp-reheat 12`).

---

## Ready‑to‑run PowerShell recipes

> Replace paths/test names as needed. All examples assume the same project root and Maven wrapper.

### 1) Biases‑only warm‑up (stabilize the head)
```powershell
python .\scripts\auto_tuning_loop.py \
  --project-root C:\Development\Chess-Engine \
  --mvn .\mvnw.cmd \
  --test julius.game.chessengine.ai.BestMoveSearchTest \
  --engine-threads 1 `
  --lazy-threads 1 `
  --root-par-limit 48 `
  --java-release 25 --preview \
  --accept-worse --accept-temp 0.03 \
  --mut-frac 0.01 --mut-frac-min 0.005 --mut-frac-max 0.02 \
  --freeze-after 40 \
  --allow-pattern "^(learning\..*\.bias$)" \
  --deny-pattern "^(evaluation\.|material\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.|moveordering\.|search\.)" \
  --noimp-reheat 20 --reheat-factor 1.3 \
  --priority-duration-metric durationMsP95 --duration-bonus-threshold 0.00 --duration-weight 0.5 \
  --max-failure-regress 0 --max-error-regress 0 \
  --jvm-gc zgc --tt-mb 1024 --engine-threads 1 --lazy-threads 1 --root-par-limit 48 \
  --xms auto --xmx auto --plan-concurrent 1 \
  --git-commit \
  --extra-maven-args -q
```

### 2) Layer‑1 connective weights (keep layer‑0 frozen)
```powershell
python .\scripts\auto_tuning_loop.py \
  --project-root C:\Development\Chess-Engine \
  --mvn .\mvnw.cmd \
  --test julius.game.chessengine.ai.BestMoveSearchTest \
  --java-release 25 --preview \
  --accept-worse --accept-temp 0.03 \
  --mut-frac 0.015 --mut-frac-min 0.01 --mut-frac-max 0.02 \
  --freeze-after 40 \
  --allow-pattern "^(learning\.layer1\.)" \
  --deny-pattern "^(evaluation\.|material\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.|moveordering\.|search\.|learning\.layer0\.)" \
  --noimp-reheat 20 --reheat-factor 1.3 \
  --priority-duration-metric durationMsP95 --duration-bonus-threshold 0.00 --duration-weight 0.5 \
  --max-failure-regress 0 --max-error-regress 0 \
  --jvm-gc zgc --tt-mb 1024 --engine-threads 1 --lazy-threads 1 --root-par-limit 48 \
  --xms auto --xmx auto --plan-concurrent 1 \
  --git-commit \
  --extra-maven-args -q
```

### 3) Layer‑0 coarse→fine (neurons 0–2 first)
```powershell
# Phase A: neurons 0..2 only
python .\scripts\auto_tuning_loop.py \
  --project-root C:\Development\Chess-Engine \
  --mvn .\mvnw.cmd \
  --test julius.game.chessengine.ai.BestMoveSearchTest \
  --java-release 25 --preview \
  --accept-worse --accept-temp 0.03 \
  --mut-frac 0.01 --mut-frac-min 0.005 --mut-frac-max 0.02 \
  --freeze-after 50 \
  --allow-pattern "^(learning\.layer0\.neuron(0|1|2)\.)" \
  --deny-pattern "^(evaluation\.|material\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.|moveordering\.|search\.)" \
  --noimp-reheat 18 --reheat-factor 1.35 \
  --priority-duration-metric durationMsP95 --duration-bonus-threshold 0.00 --duration-weight 0.55 \
  --max-failure-regress 0 --max-error-regress 0 \
  --jvm-gc zgc --tt-mb 1024 --engine-threads 1 --lazy-threads 1 --root-par-limit 48 \
  --xms auto --xmx auto --plan-concurrent 1 \
  --git-commit \
  --extra-maven-args -q

# Phase B: then open all layer0 neurons
python .\scripts\auto_tuning_loop.py \
  --project-root C:\Development\Chess-Engine \
  --mvn .\mvnw.cmd \
  --test julius.game.chessengine.ai.BestMoveSearchTest \
  --java-release 25 --preview \
  --accept-worse --accept-temp 0.03 \
  --mut-frac 0.015 --mut-frac-min 0.01 --mut-frac-max 0.03 \
  --freeze-after 50 \
  --allow-pattern "^(learning\.layer0\.)" \
  --deny-pattern "^(evaluation\.|material\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.|moveordering\.|search\.)" \
  --noimp-reheat 18 --reheat-factor 1.35 \
  --priority-duration-metric durationMsP95 --duration-bonus-threshold 0.00 --duration-weight 0.55 \
  --max-failure-regress 0 --max-error-regress 0 \
  --jvm-gc zgc --tt-mb 1024 --engine-threads 1 --lazy-threads 1 --root-par-limit 48 \
  --xms auto --xmx auto --plan-concurrent 1 \
  --git-commit \
  --extra-maven-args -q
```

### 4) Weights‑only (keep learned biases fixed)
```powershell
python .\scripts\auto_tuning_loop.py \
  --project-root C:\Development\Chess-Engine \
  --mvn .\mvnw.cmd \
  --test julius.game.chessengine.ai.BestMoveSearchTest \
  --java-release 25 --preview \
  --accept-worse --accept-temp 0.03 \
  --mut-frac 0.012 --mut-frac-min 0.008 --mut-frac-max 0.02 \
  --freeze-after 50 \
  --allow-pattern "^(learning\..*\.weight\d+)$" \
  --deny-pattern "^(evaluation\.|material\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.|moveordering\.|search\.)" \
  --noimp-reheat 20 --reheat-factor 1.3 \
  --priority-duration-metric durationMsP95 --duration-bonus-threshold 0.00 --duration-weight 0.55 \
  --max-failure-regress 0 --max-error-regress 0 \
  --jvm-gc zgc --tt-mb 1024 --engine-threads 1 --lazy-threads 1 --root-par-limit 48 \
  --xms auto --xmx auto --plan-concurrent 1 \
  --git-commit \
  --extra-maven-args -q
```

---

## Practical tips

- **Keep Syzygy config constant** during these runs to avoid masking small score deltas.
- If weights **explode** or flip signs too often, lower `--temp-start` or use `--mut-frac-min 0.003`.
- If nothing improves for many iterations, *reheat* and/or expand the allow‑pattern to include the next group of neurons.
- Once you find a good NN head, consider **re‑enabling a tiny slice** of classical eval (e.g., `PawnStructureModule.midgame: 0.2`) and run a short mixed pass with `--allow-pattern "^(learning\.|evaluation\.modules\.(pawnstructuremodule|kingsafetymodule)\.)"`.
- Commit checkpoints on improvement (`--git-commit`) so you can bisect learned heads.

---

## Done‑for‑you allow/deny snippets

Copy‑paste and swap as needed:

```powershell
# All learning
--allow-pattern "^(learning\.)"

# Biases only
--allow-pattern "^(learning\..*\.bias$)"

# Weights only
--allow-pattern "^(learning\..*\.weight\d+)$"

# Layer 0 only
--allow-pattern "^(learning\.layer0\.)"

# Layer 1 only
--allow-pattern "^(learning\.layer1\.)"

# Layer 0, neurons 0..2
--allow-pattern "^(learning\.layer0\.neuron(0|1|2)\.)"

# Keep classic eval/search locked while training the head
--deny-pattern "^(evaluation\.|material\.|pawnstructure\.|activity\.|development\.|kingsafety\.|threat\.|moveordering\.|search\.)"
```
```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --java-release 25 `
  --preview `
  --accept-worse `
  --accept-temp 0.08 `
  --mut-frac 0.02 `
  --mut-frac-min 0.02 `
  --mut-frac-max 0.04 `
  --freeze-after 12 `
  --allow-pattern '^(learning\.)' `
  --noimp-reheat 12 `
  --reheat-factor 1.7 `
  --jvm-gc zgc `
  --tt-mb 1024 `
  --engine-threads 1 `
  --lazy-threads 1 `
  --root-par-limit 48 `
  --xms auto `
  --xmx auto `
  --plan-concurrent 1 `
  --priority-duration-metric durationMsP95 `
  --duration-bonus-threshold 0.20 `
  --max-failure-regress 1 `
  --max-error-regress 0 `
  --duration-weight 1.1 `
  --git-commit `
  --extra-maven-args -q
```

```powershell
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --java-release 25 `
  --preview `
  --accept-worse `
  --accept-temp 0.12 `
  --temp-start 0.55 `
  --temp-min 0.06 `
  --temp-decay 16 `
  --mut-frac 0.50 `
  --mut-frac-min 0.08 `
  --mut-frac-max 0.50 `
  --freeze-after 40 `
  --allow-pattern '^(learning\.)' `
  --noimp-reheat 12 `
  --reheat-factor 1.7 `
  --jvm-gc zgc `
  --tt-mb 1024 `
  --engine-threads 1 `
  --lazy-threads 1 `
  --root-par-limit 48 `
  --xms auto `
  --xmx auto `
  --plan-concurrent 1 `
  --priority-duration-metric durationMsP95 `
  --duration-bonus-threshold 0.20 `
  --max-failure-regress 1 `
  --max-error-regress 0 `
  --duration-weight 1.1 `
  --git-commit `
  --extra-maven-args -q
```

python .\scripts\cutechess_tuning_loop.py --project-root C:\Development\Chess-Engine `
--cutechess-cli "C:\Program Files (x86)\Cute Chess\cutechess-cli.exe" --stockfish C:\Development\cutechess\stockfish\stockfish-windows-x86-64-avx2.exe `
--java java --engine-name Alieknek `
--opponent-name SF --opponent-elo 1750 `
--time-control 10+0.1 --concurrency 3 `
--rounds 10 --pgn-out C:\Development\cutechess\match_tuning.pgn `
--engine-gc zgc --engine-active-processor-count 24 `
--engine-tt-mb 1024 --engine-threads 1 `
--engine-lazy-threads 1 --mut-frac 0.22 `
--mut-frac-min 0.18 --mut-frac-max 0.28 `
--temp-start 0.25 --temp-min 0.05 `
--temp-decay 14 --spectral-base 0.70 `
--reheat-factor 1.0 --accept-worse `
--accept-temp 0.08 --build-jar `
--allow-pattern "^(learning.)" 