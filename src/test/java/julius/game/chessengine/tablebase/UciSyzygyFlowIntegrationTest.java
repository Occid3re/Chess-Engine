package julius.game.chessengine.tablebase;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.uci.UciHandler;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class UciSyzygyFlowIntegrationTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final String BLACK_WIN_FEN = "8/8/8/8/8/1kb1n3/8/2K5 b - - 31 16";
    private static final String WHITE_WIN_FEN = "3k4/p6p/8/8/8/4N3/5B2/3K4 w - - 0 1";

    @Test
    void blackSyzygyWinIsSurfacedThroughUciFlow() throws Exception {
        TablebaseTestSupport.assumeSyzygyConfigured();
        runSyzygyFlowScenario(BLACK_WIN_FEN, false);
    }

    @Test
    void whiteSyzygyWinIsSurfacedThroughUciFlow() throws Exception {
        TablebaseTestSupport.assumeSyzygyConfigured();
        runSyzygyFlowScenario(WHITE_WIN_FEN, true);
    }

    private void runSyzygyFlowScenario(String fen, boolean expectedWhiteToMove) throws Exception {
        String directories = TablebaseTestSupport.requireConfiguredSyzygyDirectories();

        String previousProperty = System.getProperty("chessengine.syzygy.paths");
        System.setProperty("chessengine.syzygy.paths", directories);

        Field scoreField = Score.class.getDeclaredField("TABLEBASE_SERVICE");
        scoreField.setAccessible(true);
        SyzygyTablebaseService previousScoreService = (SyzygyTablebaseService) scoreField.get(null);

        List<String> output = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);

        UciHandler handler = new UciHandler(output::add, running::get);
        try {
            handler.handle("uci");
            awaitLine(output, line -> line.equals("uciok"), "uciok");

            handler.handle("isready");
            awaitLine(output, line -> line.equals("readyok"), "readyok");

            handler.handle("ucinewgame");
            handler.handle("position fen " + fen);

            Engine engine = extractEngine(handler);
            assertThat(engine.whitesTurn()).isEqualTo(expectedWhiteToMove);

            TablebaseResult initialResult = engine.getLastTablebaseResult()
                    .orElseThrow(() -> new AssertionError("Expected initial tablebase result for FEN: " + fen));
            assertThat(initialResult.wdl()).isEqualTo(SyzygyWdl.WIN);

            handler.handle("go depth 1");
            String bestmove = awaitLine(output, line -> line.startsWith("bestmove"), "bestmove");
            assertThat(bestmove).doesNotContain("(none)");

            List<String> snapshot = snapshot(output);
            assertThat(snapshot.stream().anyMatch(line -> line.startsWith("info") && line.contains("score mate")))
                    .as("UCI info should expose mate score").isTrue();
            String dtzLine = snapshot.stream()
                    .filter(line -> line.contains("info string tablebase dtz"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing tablebase DTZ info in output: " + snapshot));
            int dtz = parseTrailingInt(dtzLine);
            assertThat(dtz).isNotZero();

        } finally {
            running.set(false);
            handler.stop();
            restoreProperty(previousProperty);
            restoreScoreService(scoreField, previousScoreService);
        }
    }

    private static Engine extractEngine(UciHandler handler) throws Exception {
        Field engineField = UciHandler.class.getDeclaredField("engine");
        engineField.setAccessible(true);
        return (Engine) engineField.get(handler);
    }

    private static void restoreProperty(String previous) {
        if (previous == null) {
            System.clearProperty("chessengine.syzygy.paths");
        } else {
            System.setProperty("chessengine.syzygy.paths", previous);
        }
    }

    private static void restoreScoreService(Field scoreField, SyzygyTablebaseService service) throws IllegalAccessException {
        if (service == null) {
            scoreField.setAccessible(true);
            scoreField.set(null, null);
        } else {
            Score.setTablebaseService(service);
        }
    }

    private static String awaitLine(List<String> output, Predicate<String> predicate, String description) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (output) {
                for (String line : output) {
                    if (predicate.test(line)) {
                        return line;
                    }
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for " + description + ". Output: " + snapshot(output));
    }

    private static List<String> snapshot(List<String> output) {
        synchronized (output) {
            return List.copyOf(output);
        }
    }

    private static int parseTrailingInt(String line) {
        String trimmed = line.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace < 0 || lastSpace == trimmed.length() - 1) {
            throw new IllegalArgumentException("Unable to parse trailing int from line: " + line);
        }
        return Integer.parseInt(trimmed.substring(lastSpace + 1));
    }
}
