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

### Additional notes
* `MateSearchTest` keeps the shorter time budgets (50–500ms) for diagnostics only. The suite now requires the engine to
  succeed when it receives the largest configured per-move budget (currently 10s), so occasional misses at lower limits are
  acceptable.
* Evaluation weights & tuning: All evaluation weights are wired through the tuning module using seed-tunings.yaml. When introducing new weights:
  Define them in the corresponding evaluation module (where the feature is computed).
  Add the same weights (with sensible defaults) to seed-tunings.yaml.
  Expose and apply them via the tuning module so they are loaded and propagated into the evaluation at runtime.

### BestMoveSearchTest progress notes (2025-10-05)
* Added SEE-driven capture nudges in `AI.java` so losing trades get a 0.05 penalty while winning captures receive a scaled bonus (capped at 0.03 after a 20 cp margin).
* Introduced quiet-move ordering bonuses that favour castling, knight centralisation, and releasing bishops from their starting squares while demoting early rook-pawn pushes and premature king walks.
* These adjustments reduce the failing cases in `BestMoveSearchTest` from 24 (baseline) to 21 on the default single-thread configuration. Use the standard single-class Maven command above to verify.