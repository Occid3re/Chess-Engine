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

