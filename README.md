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

