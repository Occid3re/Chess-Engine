package julius.game.chessengine.uci;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Entry point for running the engine via the UCI protocol on standard IO.
 * Runs the 5.x core engine by default; {@code -Dchessengine.core=legacy} selects the 4.x engine.
 */
public final class UciMain {

    private UciMain() {
    }

    public static void main(String[] args) throws IOException {
        if (!"legacy".equalsIgnoreCase(System.getProperty("chessengine.core", "core"))) {
            julius.game.chessengine.core.Uci.main(args);
            return;
        }
        UciHandler handler = new UciHandler(System.out::println, () -> true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!handler.handle(line)) {
                break;
            }
        }
    }
}

