package testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Utility used by diagnostic-heavy tests to persist structured log output under
 * {@code target/test-diagnostics}. The goal is to make it trivial for humans
 * (and future agents) to aggregate per-position telemetry even when individual
 * parameterised runs fail.
 */
public final class TestReportWriter {

    private static final Path OUTPUT_DIR = Paths.get("target", "test-diagnostics");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private TestReportWriter() {
    }

    /**
     * Writes the provided lines to {@code target/test-diagnostics/<fileName>}. The
     * file is replaced on every invocation so consecutive Maven runs always
     * produce a clean artifact. If the write fails, the error is printed to
     * {@code System.err} but no exception bubbles up to the caller.
     */
    public static synchronized Path writeLines(String fileName, List<String> lines) {
        Objects.requireNonNull(fileName, "fileName");
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        Path file = OUTPUT_DIR.resolve(fileName);
        try {
            Files.createDirectories(OUTPUT_DIR);
            Files.writeString(
                    file,
                    String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            return file;
        } catch (IOException ioe) {
            System.err.println("[TestReportWriter] Failed to write diagnostics to " + file + ": " + ioe.getMessage());
            return null;
        }
    }

    /**
     * Appends a single JSON (or text) line to the specified diagnostics file.
     * The file is created on demand and timestamped entries are prepended to
     * make correlation with console output easier.
     */
    public static synchronized void appendLine(String fileName, String line) {
        Objects.requireNonNull(fileName, "fileName");
        if (line == null || line.isEmpty()) {
            return;
        }
        Path file = OUTPUT_DIR.resolve(fileName);
        try {
            Files.createDirectories(OUTPUT_DIR);
            String payload = TIMESTAMP_FORMAT.format(Instant.now()) + " " + line + System.lineSeparator();
            Files.writeString(
                    file,
                    payload,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ioe) {
            System.err.println("[TestReportWriter] Failed to append diagnostics to " + file + ": " + ioe.getMessage());
        }
    }
}
