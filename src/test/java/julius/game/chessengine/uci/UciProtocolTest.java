package julius.game.chessengine.uci;

import org.junit.jupiter.api.Test;

import julius.game.chessengine.ai.AI;
import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.board.MoveList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration style tests verifying basic UCI command exchanges over
 * standard input/output.
 */
public class UciProtocolTest {

    @Test
    void testUciIsreadyPositionGoQuit() throws Exception {
        String commands = String.join("\n",
                "uci",
                "isready",
                "position startpos",
                "go movetime 200",
                "isready",
                "quit") + "\n";

        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true));

            UciMain.main(new String[0]);

            // wait until bestmove is printed or timeout after ~2s
            for (int i = 0; i < 40; i++) {
                if (out.toString().contains("bestmove")) {
                    break;
                }
                Thread.sleep(50);
            }
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }

        String output = out.toString();
        assertTrue(output.contains("id name Alieknek "), output);
        assertTrue(output.contains("uciok"), output);
        assertTrue(output.contains("readyok"), output);
        assertTrue(output.contains("bestmove"), output);
    }

    @Test
    void testQuitReturnsFalse() {
        UciHandler handler = new UciHandler();
        assertFalse(handler.handle("quit"));
    }

    @Test
    void testIsreadyStopsOngoingSearch() throws Exception {
        String commands = String.join("\n",
                "uci",
                "position startpos",
                "go movetime 1000",
                "isready",
                "quit") + "\n";

        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true));

            UciMain.main(new String[0]);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }

        String output = out.toString();
        long bestmoveCount = output.lines().filter(l -> l.startsWith("bestmove")).count();
        assertEquals(1, bestmoveCount, output);
        assertTrue(output.contains("readyok"), output);
        assertTrue(output.indexOf("bestmove") < output.indexOf("readyok"), output);
    }

    @Test
    void testGoPonderRequiresPonderhitForBestmove() throws Exception {
        List<String> outputs = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        UciHandler handler = new UciHandler(outputs::add, running::get);

        handler.handle("position startpos");
        handler.handle("go ponder wtime 1000 btime 1000");

        // give the search some time to start
        Thread.sleep(200);

        synchronized (outputs) {
            assertTrue(outputs.stream().noneMatch(line -> line.startsWith("bestmove")), outputs.toString());
        }

        handler.handle("ponderhit");

        String bestmove = awaitBestmove(outputs, 5_000L);
        assertNotEquals("bestmove 0000", bestmove);
    }

    @Test
    void testGoPonderStopOutputsNullMove() throws Exception {
        List<String> outputs = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        UciHandler handler = new UciHandler(outputs::add, running::get);

        handler.handle("position startpos");
        handler.handle("go ponder wtime 60000 btime 60000");

        Thread.sleep(200);

        handler.handle("stop");

        String bestmove = awaitBestmove(outputs, 5_000L);
        assertEquals("bestmove 0000", bestmove);
    }

    @Test
    void testPonderhitSetsFlagWhenPondering() throws Exception {
        List<String> outputs = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        UciHandler handler = new UciHandler(outputs::add, running::get);

        Engine engine = extractEngine(handler);
        StubAI stubAi = new StubAI(engine);
        replaceAi(handler, stubAi);

        handler.handle("position startpos");
        handler.handle("go ponder wtime 60000 btime 60000");

        waitForCondition(() -> getBooleanField(handler, "ponderActive"), 1_000L,
                "ponderActive was not set");
        assertFalse(getBooleanField(handler, "ponderShouldOutputMove"));

        handler.handle("ponderhit");
        assertTrue(getBooleanField(handler, "ponderShouldOutputMove"));

        MoveList legal = engine.getAllLegalMoves();
        stubAi.setBestMove(legal.getMove(0));

        String bestmove = awaitBestmove(outputs, 5_000L);
        assertTrue(bestmove.startsWith("bestmove "), bestmove);

        waitForCondition(() -> !getBooleanField(handler, "ponderActive"), 1_000L,
                "ponderActive did not reset");
    }

    @Test
    void testPonderhitIgnoredWhenNotPondering() throws Exception {
        List<String> outputs = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        UciHandler handler = new UciHandler(outputs::add, running::get);

        Engine engine = extractEngine(handler);
        StubAI stubAi = new StubAI(engine);
        replaceAi(handler, stubAi);

        handler.handle("position startpos");
        handler.handle("go wtime 60000 btime 60000");

        waitForCondition(() -> getSearchThread(handler) != null, 1_000L,
                "search thread was not started");
        assertFalse(getBooleanField(handler, "ponderActive"));

        handler.handle("ponderhit");
        assertFalse(getBooleanField(handler, "ponderShouldOutputMove"));

        MoveList legal = engine.getAllLegalMoves();
        stubAi.setBestMove(legal.getMove(0));

        String bestmove = awaitBestmove(outputs, 5_000L);
        assertTrue(bestmove.startsWith("bestmove "), bestmove);

        assertFalse(getBooleanField(handler, "ponderActive"));
        assertFalse(getBooleanField(handler, "ponderShouldOutputMove"));
    }

    private static String awaitBestmove(List<String> outputs, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String lastBestmove = null;
        while (System.currentTimeMillis() < deadline) {
            synchronized (outputs) {
                for (String line : outputs) {
                    if (line.startsWith("bestmove")) {
                        lastBestmove = line;
                    }
                }
            }
            if (lastBestmove != null) {
                return lastBestmove;
            }
            Thread.sleep(50);
        }
        fail("No bestmove within timeout: " + outputs);
        return null;
    }

    private static Engine extractEngine(UciHandler handler) {
        try {
            Field field = UciHandler.class.getDeclaredField("engine");
            field.setAccessible(true);
            return (Engine) field.get(handler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void replaceAi(UciHandler handler, AI replacement) {
        try {
            Field field = UciHandler.class.getDeclaredField("ai");
            field.setAccessible(true);
            field.set(handler, replacement);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean getBooleanField(UciHandler handler, String fieldName) {
        try {
            Field field = UciHandler.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getBoolean(handler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Thread getSearchThread(UciHandler handler) {
        try {
            Field field = UciHandler.class.getDeclaredField("searchThread");
            field.setAccessible(true);
            return (Thread) field.get(handler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void waitForCondition(BooleanSupplier condition, long timeoutMillis, String failureMessage)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        fail(failureMessage);
    }

    private static final class StubAI extends AI {
        private final AtomicInteger bestMove = new AtomicInteger(-1);
        private volatile List<MoveAndScore> principalVariation = Collections.emptyList();

        StubAI(Engine engine) {
            super(engine);
        }

        @Override
        public void startAutoPlay(boolean aiIsWhite, boolean aiIsBlack) {
            // Intentionally do nothing to avoid starting background search threads.
        }

        @Override
        public void stopCalculation() {
            // Nothing to stop because startAutoPlay() is a no-op in this stub.
        }

        @Override
        public Integer getCurrentBestMoveInt() {
            return bestMove.get();
        }

        void setBestMove(int move) {
            bestMove.set(move);
        }

        @Override
        public List<MoveAndScore> getCalculatedLine() {
            return principalVariation;
        }
    }
}
