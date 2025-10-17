# Tasks

- [ ] Fix `searchResultReady` publication ordering so the updated PV list in `AI.java` is visible and consistent for other threads before they read the flag. See `AI.java:1321`, `AI.java:1337`, `AI.java:1740-1840`.
- [ ] Make the global search counters in `AI.java` (`nodesVisited`, `nullMoveCount`) thread-safe under Lazy SMP/root-split search, e.g., by switching to `LongAdder` or aggregating per-thread. See `AI.java:298-300`, `AI.java:2204`, `AI.java:2280`.
- [ ] Convert stored mate scores back to human-readable values when reconstructing the PV from the transposition table in `AI.java` so the PV output does not display distance-adjusted mate values. See `AI.java:1840`, `AI.java:2232`, `AI.java:2717-2995`.
