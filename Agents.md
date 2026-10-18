# Agents.md

Project instructions for agents and humans are in [CLAUDE.md](CLAUDE.md). The essentials:

- Build: `./mvnw.cmd -DskipTests package` (Java 25, preview enabled) -> `target/chess-engine-<version>-uci.jar`
- Tests: `./mvnw.cmd test`; Python tests in `scripts/tests` (pytest)
- The engine is `julius.game.chessengine.core` (no dependencies). Judge changes by self-play (SPRT via
  `E:\ChessEngines\harness` or `scripts/tuning/cutechess.py`), not by static test positions.
- One branch per release (`v4.0.7` = Alieknek 4.0.7); a new release gets a new branch from the previous one.
  The server deployment lives in the Occid3re/play repo and pins an engine commit.
