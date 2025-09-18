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

## Running the web UI

To use the HTML/JavaScript frontend, start the Spring Boot application instead
of the standalone UCI main class. You can either run
`julius.game.chessengine.ChessEngineApplication` from your IDE or execute:

```bash
./mvnw spring-boot:run
```

With the application running, open `http://localhost:8080` in your browser. The
static assets are served from the running application and the page connects to
the embedded WebSocket endpoint at `/ws/uci`.

If you prefer to open `index.html` directly from disk (for example from within
IntelliJ), keep the Spring Boot application running in the background. When the
page is loaded via the `file://` protocol, it automatically falls back to
`ws://localhost:8080/ws/uci`, so the UI remains fully functional as long as the
backend is reachable at that address.

