package julius.game.chessengine.uci;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Entry point for running the engine via the UCI protocol on standard IO.
 */
public final class UciMain {

    private UciMain() {
    }

    public static void main(String[] args) throws IOException {
        Path logFile = Path.of("logs", "uci-communication.log");
        try (UciLogger logger = new UciLogger(logFile)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            UciHandler handler = new UciHandler(message -> {
                logger.logSent(message);
                System.out.println(message);
            }, () -> true, logger);
            String line;
            while ((line = reader.readLine()) != null) {
                logger.logReceived(line);
                if (!handler.handle(line)) {
                    break;
                }
            }
        }
    }
}

