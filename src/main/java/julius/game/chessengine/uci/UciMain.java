package julius.game.chessengine.uci;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Entry point for running the engine via the UCI protocol on standard IO.
 */
public final class UciMain {

    private UciMain() {
    }

    public static void main(String[] args) throws IOException {
        UciHandler handler = new UciHandler();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!handler.handle(line)) {
                break;
            }
        }
    }
}

