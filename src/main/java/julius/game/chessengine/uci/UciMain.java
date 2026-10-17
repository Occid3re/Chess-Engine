package julius.game.chessengine.uci;

import java.io.IOException;

/**
 * Entry point of the runnable jar (chess-engine-*-uci.jar): the UCI engine on standard IO.
 * The engine itself lives in {@code julius.game.chessengine.core}; this class only keeps the jar's
 * main class stable for the Lichess bot and GUIs.
 */
public final class UciMain {

    private UciMain() {
    }

    public static void main(String[] args) throws IOException {
        julius.game.chessengine.core.Uci.main(args);
    }
}
