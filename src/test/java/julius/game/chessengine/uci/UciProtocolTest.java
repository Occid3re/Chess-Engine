package julius.game.chessengine.uci;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void stopUsesPublishedBestMoveInsteadOfFallback() throws Exception {
        var tablebaseService = mock(julius.game.chessengine.syzygy.SyzygyTablebaseService.class);
        when(tablebaseService.getConfiguredDirectories()).thenReturn(null);

        var engine = mock(julius.game.chessengine.engine.Engine.class);
        var gameState = mock(julius.game.chessengine.engine.GameState.class);
        when(engine.getGameState()).thenReturn(gameState);
        when(gameState.isTerminal()).thenReturn(false);
        when(gameState.getState()).thenReturn(julius.game.chessengine.engine.GameStateEnum.PLAY);
        when(engine.getLastTablebaseResult()).thenReturn(Optional.empty());

        int fallbackMove = julius.game.chessengine.board.MoveHelper.createMoveInt(
                julius.game.chessengine.board.MoveHelper.convertStringToIndex("e2"),
                julius.game.chessengine.board.MoveHelper.convertStringToIndex("e4"),
                julius.game.chessengine.figures.PieceType.PAWN,
                true, false, false, false, null, null, false, false, 0);
        int bestMove = julius.game.chessengine.board.MoveHelper.createMoveInt(
                julius.game.chessengine.board.MoveHelper.convertStringToIndex("g1"),
                julius.game.chessengine.board.MoveHelper.convertStringToIndex("f3"),
                julius.game.chessengine.figures.PieceType.KNIGHT,
                true, false, false, false, null, null, false, false, 0);

        IntArrayList legalMoves = new IntArrayList();
        legalMoves.add(fallbackMove);
        legalMoves.add(bestMove);
        when(engine.getAllLegalMoves()).thenReturn(legalMoves);

        var ai = mock(julius.game.chessengine.ai.AI.class);
        when(ai.getMainEngine()).thenReturn(engine);
        when(ai.getCalculatedLine()).thenReturn(List.of());
        when(ai.getNodesVisited()).thenReturn(0L);

        AtomicInteger bestMoveRef = new AtomicInteger(-1);
        when(ai.getCurrentBestMoveInt()).then(inv -> bestMoveRef.get());
        doAnswer(inv -> {
            bestMoveRef.set(bestMove);
            return null;
        }).when(ai).startAutoPlay(anyBoolean(), anyBoolean());
        doAnswer(inv -> {
            bestMoveRef.set(-1);
            return null;
        }).when(ai).stopCalculation();
        doAnswer(inv -> null).when(ai).requestStop();

        Consumer<String> outputCollector;
        List<String> outputs = Collections.synchronizedList(new ArrayList<>());
        outputCollector = outputs::add;

        UciHandler handler = new UciHandler(tablebaseService, engine, ai, outputCollector, () -> true);

        handler.handle("position startpos");
        handler.handle("go movetime 1000");
        handler.handle("stop");

        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (outputs.stream().noneMatch(line -> line.startsWith("bestmove")) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        String bestmoveLine = outputs.stream()
                .filter(line -> line.startsWith("bestmove"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("bestmove not emitted: " + outputs));

        assertEquals("bestmove g1f3", bestmoveLine, outputs.toString());
    }
}
