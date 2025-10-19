# Chess Engine

This project builds a UCI-compatible chess engine. The build now produces an
executable JAR that bundles all dependencies and specifies
`julius.game.chessengine.uci.UciMain` as the manifest `Main-Class`.

## Building

```bash
./mvnw package
```

The command above creates
`target/chess-engine-<version>-uci.jar`.

## Running

Launch the engine without adding anything to the classpath:

```bash
java -jar target/chess-engine-<version>-uci.jar
```

The process listens on standard input and speaks the UCI protocol on standard
output.

## Profiling the engine on Windows

To automate the build, self-play session, and profiling capture, run the
`profile-engine.bat` helper from a Windows terminal:

```cmd
profile-engine.bat
```

The script performs the following steps:

1. Builds the project with `mvnw.cmd -DskipTests package`.
2. Locates the latest `*-uci.jar` artifact in `target/`.
3. Launches the UCI engine with Java Flight Recorder (JFR) profiling enabled
   while the engine plays both sides of a game for a configurable number of
   plies and move time.
4. Stores the resulting artifacts under `profiles/`:
   * `uci-<timestamp>.jfr` &mdash; the JFR capture that you can inspect with
     Java Mission Control.
   * `uci-<timestamp>.log` &mdash; the raw UCI transcript, including the
     commands sent to the engine and the `info`/`bestmove` responses.
   * `uci-<timestamp>-summary.json` &mdash; metadata about the run, including
     the moves that were played and the effective configuration.

You can tweak the duration and game characteristics by exporting environment
variables before running the batch file:

```cmd
set PROFILE_MOVE_TIME_MS=1500
set PROFILE_PLY_COUNT=120
set PROFILE_JFR_DURATION=240s
profile-engine.bat
```

Adjust `PROFILE_JFR_DURATION` to ensure the recording lasts long enough to
cover the requested number of plies. The batch file automatically validates that
Java is available on the `PATH` and reports any build or runtime errors.

## Running the web UI

The browser UI talks to the engine through a WebSocket that is hosted by the
Spring Boot application. You **do not** need to start `UciMain` separately; each
browser tab gets its own engine instance through the WebSocket handler.

Follow these steps to get the frontend running and play a game:

1. Build the project (optional, but makes sure all resources are generated):

   ```bash
   ./mvnw clean package
   ```

2. Start the Spring Boot app so the static files and WebSocket endpoint become
   available:

   ```bash
   ./mvnw spring-boot:run
   ```

   Wait until the logs contain `Started ChessEngineApplication`.

3. Open `http://localhost:8080/index.html` in your browser. The page should show
   the toolbar and an empty chessboard. The info bar initially displays
   `Loading...` while the WebSocket performs the `uci`/`isready` handshake and
   switches to `Engine ready` once the backend responds.

4. Click **Play as White** or **Play as Black** to pick a side, then drag a piece
   to make your first move. The engine will automatically reply on its turn. You
   can also:

   * Press **Computer Move** to force the engine to play the side to move.
   * Toggle **Autoplay** to let the engine play both sides at the configured
     move time (adjust the slider under the board).
   * Use **Reset Board**, **Undo**, **Redo**, **Import from FEN**, and **PGN** to
     manage the current game.

If the page stays on `Loading...`, check that the Spring Boot process is still
running and that your browser allows WebSocket connections to
`ws://localhost:8080/ws/uci`. Corporate VPNs or browser extensions that block
WebSockets can prevent the engine from connecting.

You can also open `src/main/resources/static/index.html` directly from disk (for
example via `file://`). Keep the Spring Boot application running in the
background: the page automatically connects to `ws://localhost:8080/ws/uci` when
it is not served by the app itself.

## Runtime flags for single-thread vs. parallel diagnostics

The production engine honours a few JVM system properties that determine how
many threads participate in the search and whether additional instrumentation is
enabled. The table below lists the most common combinations.

| Scenario | Command-line flags | Notes |
|----------|-------------------|-------|
| **Single-threaded baseline** | `-Dchessengine.searchThreads=1 -Dchessengine.lazySmpThreads=1 -Dchessengine.rootParallelLimit=24 -Dchessengine.tt.mb=64` | Matches the original 3 s depth‑8 reference run. |
| **Multi-threaded production run** | `-Dchessengine.searchThreads=<N> -Dchessengine.lazySmpThreads=<M> -Dchessengine.rootParallelLimit=<limit> -Dchessengine.tt.mb=<MB>` | Replace `<N>`, `<M>`, and `<limit>` with the desired concurrency. With 16 threads we currently use `N=16`, `M=8`, `limit=72`, `tt=256`. |
| **Parallel diagnostics (optional)** | add `-Dchessengine.diagnostics.useParallelRoot=true` | Forces `BestMoveSearchTest` to exercise the production root-split path (instead of the sequential diagnostic harness). |
| **Worker/fanout instrumentation (optional)** | add `-Dchessengine.diagnostics.workerStats=true -Dchessengine.diagnostics.rootFanout=true` | Logs Lazy SMP utilisation and root fanout events to help analyse scaling. |
| **Fanout cap (optional)** | add `-Dchessengine.rootFanoutRatio=<0.0–1.0>` | Clamps the parallel root fanout to `ratio × searchThreads` (defaults to 1.0). Useful when experimenting with smaller fanout to reduce overhead. |

Example single-thread run:

```bash
java \
  -Dchessengine.searchThreads=1 \
  -Dchessengine.lazySmpThreads=1 \
  -Dchessengine.rootParallelLimit=24 \
  -Dchessengine.tt.mb=64 \
  -jar target/chess-engine-<version>-uci.jar
```

Example parallel diagnostic run (16/8 threads with instrumentation):

```bash
java \
  -Dchessengine.searchThreads=16 \
  -Dchessengine.lazySmpThreads=8 \
  -Dchessengine.rootParallelLimit=72 \
  -Dchessengine.tt.mb=256 \
  -Dchessengine.diagnostics.useParallelRoot=true \
  -Dchessengine.diagnostics.workerStats=true \
  -Dchessengine.diagnostics.rootFanout=true \
  -Dchessengine.rootFanoutRatio=0.75 \
  -jar target/chess-engine-<version>-uci.jar
```

Adjust the ratios and cache sizes to match the hardware available in your
production environment; the values above correspond to the current multi-thread
diagnostic runs in `Tasks.md`.

## Engine tuning and self-play

The engine can now load search and evaluation parameters from an external YAML
file. Point the Spring Boot application at your configuration with

```bash
java -Dchessengine.tuning.file=/path/to/tunings.yaml -jar target/chess-engine-<version>-uci.jar
```

Each entry under the top-level `population` key defines an engine variant. The
sample file in `src/main/resources/tuning/sample-tunings.yaml` demonstrates the
structure and shows how to tweak search depth, hash size, and module weights.

For automated exploration you can use the `GeneticOptimizer` together with the
`MatchRunner`. The optimiser stages round-robin matches between configurations
with identical thread counts, keeps the strongest performers, and mutates them
into the next generation. This workflow makes it possible to evolve evaluation
parameters while keeping the UCI engine executable unchanged.

### Running the genetic tuner from the command line

Build the project and point the bootable JAR at the new `GeneticTuningMain`
entry point. Provide a seed YAML file (for example the
`src/main/resources/tuning/sample-tunings.yaml` document) and adjust the
arguments to control the optimisation loop:

```bash
./mvnw -DskipTests package
java -Dloader.main=julius.game.chessengine.tuning.GeneticTuningMain \
     -jar target/chess-engine-<version>-uci.jar \
     --seed /path/to/seed-tunings.yaml \
     --generations 10 \
     --population 12 \
     --matches  /path/to/match-log.csv
```

The command above evolves the population, prints a leaderboard of the strongest
configurations, saves the new YAML file next to the seed (or to the location
specified with `--output`), and optionally records every self-play result to the
`--matches` CSV. Omit advanced flags to fall back to the defaults specified in
`GeneticOptions.defaults()`.

Use the optional `--match-threads` flag to run multiple self-play games at the
same time when you have spare CPU cores. The tuner defaults to the number of
available processors so you can fully utilise the machine without additional
arguments.

## Move-ordering priority store

The search reuses a persistent priority table to bias move ordering. On
startup the engine looks for
`target/engine-data/move-ordering/move-ordering-priority.txt` and logs how many
entries were loaded. If the file is missing it simply starts with an empty
table. The checked-in seed lives under
`src/main/resources/move-ordering/move-ordering-priority.txt`; regular Maven
builds copy it into the target directory so developers always get the baseline
history without extra steps.

To regenerate the history from PGNs, enable the `move-ordering-train` profile
so the importer runs during the build. The default configuration streams every
PGN under `E:/Engine-Pgns`, skips draws, requires at least one player to meet
the ELO threshold, and writes the refreshed store to the target directory.

```bash
mvn -Pmove-ordering-train \
    -Dmove.ordering.pgnDir=E:/Engine-Pgns \
    -Dmove.ordering.minElo=3000 \
    -Dcmake.skip=true
```

Review the generated `target/engine-data/move-ordering/move-ordering-priority.txt`
and, when you are satisfied, copy it back into
`src/main/resources/move-ordering/move-ordering-priority.txt` so future builds
pick up the updated seed.

## Syzygy Tablebases

The engine understands Syzygy endgame tablebases and uses them to bypass static evaluation
whenever six or fewer pieces remain on the board. Follow these steps to get the probes running:

1. Download the five- and six-piece archives from a trusted mirror (for example
   https://tablebase.sesse.net/syzygy/). Keep the `.rtbw` and `.rtbz` files together and extract
   them to a local directory (e.g. `C:\tb\syzygy`).
2. Point the engine at the directory via the `chessengine.syzygy.path` system property (use
   `chessengine.syzygy.paths` when you want to provide several locations separated by the platform
   path separator). The engine expands directories that merely group tablebases (for example a
   parent folder containing `3-4-5-wdl/`, `3-4-5-dtz/`, etc.) so you only have to point at the
   root once.
   * UCI: `setoption name SyzygyPath value C:\tb\syzygy`
   * CLI: `java -Dchessengine.syzygy.path=C:\tb\syzygy -jar target/chess-engine-<version>-uci.jar`
   * Spring Boot: update `application.yaml` (see the example in this repository) or export the
     `CHESSENGINE_SYZYGY_PATH` environment variable.
   * Native override: when you do not bundle `JSyzygy` inside the application JAR, set the
     `chessengine.syzygy.nativeLibrary` system property (or `CHESSENGINE_SYZYGY_NATIVE` environment
     variable) to the full path of the native library (`JSyzygy.dll`, `libJSyzygy.so`, ...).
3. Restart the engine so the service warms up the table metadata. When a probe succeeds the console
   starts emitting `info string tablebase ...` lines (including `dtz` updates), and the search score
   snaps to the exact WDL converted from the tablebase answer.

For stress tests you can cap the tablebase usage with `-Dchessengine.syzygy.maxPieces=<n>` and adjust
`-Dchessengine.syzygy.cacheSize=<entries>` to tune the in-process probe cache.

### Credits

This project vendors MIT-licensed code from [Laurens Winkelhagen's syzygy-bridge project](https://github.com/ljgw/syzygy-bridge).
The Java classes `julius.game.chessengine.syzygy.bridge.SyzygyBridge` and `julius.game.chessengine.syzygy.bridge.SyzygyConstants`,
along with the JNI shim in `src/main/native`, originate from that repository and are used under the terms of the MIT License.
Place the corresponding native binaries in `src/main/resources/natives/<platform>/` before packaging the engine.
