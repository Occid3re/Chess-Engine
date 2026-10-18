# Alieknek Chess Engine

A UCI chess engine in Java: magic bitboards, PVS search with the usual pruning (null move, LMR, RFP, ProbCut,
singular extensions), Lazy SMP, an NNUE evaluation ((768->256)x2 SCReLU with material output buckets) and
Syzygy tablebases through JNI. It plays on Lichess as [Alieknek](https://lichess.org/@/Alieknek).

## Build and run

```bash
./mvnw package                      # target/chess-engine-<version>-uci.jar (Java 25)
java --add-modules jdk.incubator.vector -jar target/chess-engine-<version>-uci.jar
java --add-modules jdk.incubator.vector -jar target/chess-engine-<version>-uci.jar bench
```

`--add-modules jdk.incubator.vector` enables the SIMD back end of the neural network (Java's Vector API, about
5 % faster); without it the engine runs the same evaluation with plain loops.

The jar speaks UCI on standard input/output. UCI options: `Hash`, `Threads`, `Move Overhead`, `SyzygyPath`,
`Ponder`, `UseNNUE`. The same settings as system properties: `-Dchessengine.tt.mb`, `-Dchessengine.threads`,
`-Dchessengine.moveOverhead`, `-Dchessengine.syzygy.paths` (plus `-Dchessengine.syzygy.nativeLibrary` when the
JNI library is not bundled), `-Dcore.useNnue=false`, `-Dcore.nnueFile=<net>`.

The engine core (`src/main/java/julius/game/chessengine/core`) has no dependencies; `javac` alone builds it,
which is what the match and training tools do.

## Browser UI

```bash
java -Dloader.main=julius.game.chessengine.ChessEngineApplication \
     -cp target/chess-engine-<version>-uci.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Then open http://localhost:8080: every browser tab gets its own engine over the `/ws/uci` WebSocket. Play as
White or Black, ask for a computer move, or let it play itself with Autoplay.

## Training and tuning

- `core.nnue.Trainer` trains the network on CPU from `fen;whiteCp;wdl` text files; `core.tools.DataGen`
  produces self-play data (with endgame start positions and Syzygy-scored results); `core.tuning.EvalTuner`
  fits the hand-crafted evaluation.
- `scripts/tuning/generations.py` evolves the network generation by generation: self-play with the best net,
  train a child, keep it only if it wins an SPRT against its parent.
- `scripts/tuning/spsa.py` tunes the search parameters in `core/Tune.java` with SPSA through cutechess-cli.

## Syzygy tablebases

Download the 3-6 piece `.rtbw`/`.rtbz` files (for example from https://tablebase.sesse.net/syzygy/), then set
`SyzygyPath` (UCI) or `-Dchessengine.syzygy.paths=<dir>`. Several directories are separated by the platform path
separator.

### Credits

This project vendors MIT-licensed code from [Laurens Winkelhagen's syzygy-bridge project](https://github.com/ljgw/syzygy-bridge).
The Java classes `julius.game.chessengine.syzygy.bridge.SyzygyBridge` and `julius.game.chessengine.syzygy.bridge.SyzygyConstants`,
along with the JNI shim in `src/main/native`, originate from that repository and are used under the terms of the MIT License.
The Maven build compiles the JNI library with CMake and bundles it under `natives/<platform>/` in the jar.
