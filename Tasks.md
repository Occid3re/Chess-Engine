# MoveOrderingPriority – Training & Verification

### Goal

Deliver a repeatable pipeline that (a) verifies `MoveOrderingPriority` integration inside
`AI.sortMovesByEfficiency`, and (b) produces a packaged default priority file generated from
decisive games so the engine boots with useful history without extra arguments.

### Guardrails

- Keep `MoveOrderingPriority` updates deterministic: priorities must only change via the importer or in-game revision hooks.
- Persist the store under `target/engine-data/move-ordering/move-ordering-priority.txt` so packaging and Runtime defaults stay aligned.
- Avoid mutating the PGN source files; all transformations should happen in-memory.

### Workstreams

1. **Integration Verification**
   - Exercise `AI.sortMovesByEfficiency` with seeded priorities to confirm ordering shifts, backed by unit/integration coverage.
   - Continue running `MoveOrderingPriorityImporter` in `--dry-run --report` mode against a single decisive PGN to audit move-int deltas before writing.
2. **PGN Ingestion Pipeline**
   - Extend `OpeningPgnReader` to stream large archives without materialising every game.
   - Implement `MoveOrderingPriorityImporter` CLI (reset, reporting, verification flags) to import decisive games and cross-check the persisted store.
3. **Default Artifact Generation**
   - Seed the repository with `src/main/resources/move-ordering/move-ordering-priority.txt` and ensure Maven copies it to `target/engine-data/...` during builds.
   - `MoveOrderingPriorityImporter --reset` must re-train from the latest PGN dump and leave the verified file ready for packaging.

### Measure

```bash
# Single-game verification with move delta report
mvn -Dcmake.skip=true -Djava.version=25 -Dmaven.compiler.release=25 \
    -Dmaven.compiler.enablePreview=true -DargLine="--enable-preview" \
    -Dexec.mainClass=julius.game.chessengine.ai.MoveOrderingPriorityImporter \
    -Dexec.args='/mnt/e/Engine-Pgns/2025-10.bare.[12315].pgn --limit=1 --dry-run --report --min-elo=3000' exec:java

# Full training from scratch (decisive games only)
mvn -Dcmake.skip=true -Djava.version=25 -Dmaven.compiler.release=25 \
    -Dmaven.compiler.enablePreview=true -DargLine="--enable-preview" \
    -Dexec.mainClass=julius.game.chessengine.ai.MoveOrderingPriorityImporter \
    -Dexec.args='/mnt/e/Engine-Pgns/2025-10.bare.[12315].pgn --reset --min-elo=3000' exec:java
```

### Accept

✅ `MoveOrderingPriority` loads automatically from the packaged file with no command-line flags.  
✅ Importer verification passes (no delta mismatches) for both single-game spot checks and the full archive.  
✅ Priority file reflects only decisive games (draws skipped) and survives a clean rebuild.

### Next Steps

1. Surface a regression-friendly smoke test that reads the packaged priority file and ensures non-empty content plus monotonic ordering changes for a canned node list.
2. Document the importer workflow (flags, expected runtime, output locations) in `Agents.md` once stabilised.
3. Schedule periodic re-training (new PGN drops) via CI or a scripted task so the default history keeps pace with engine improvements.
