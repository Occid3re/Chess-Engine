package julius.game.chessengine.uci;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Utility class used for logging UCI communication to a file.
 */
public final class UciLogger implements Closeable {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final PrintWriter writer;
    private boolean closed = false;

    public UciLogger(Path logFile) throws IOException {
        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(logFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND),
                StandardCharsets.UTF_8)), true);
        logEvent("--- Logging started ---");
    }

    public void logReceived(String message) {
        log("IN", message);
    }

    public void logSent(String message) {
        log("OUT", message);
    }

    public void logEvent(String message) {
        log("EVENT", message);
    }

    private synchronized void log(String type, String message) {
        if (closed) {
            return;
        }
        writer.printf("%s [%s] %s%n", FORMATTER.format(Instant.now()), type, message);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        logEvent("--- Logging finished ---");
        writer.flush();
        writer.close();
        closed = true;
    }
}
