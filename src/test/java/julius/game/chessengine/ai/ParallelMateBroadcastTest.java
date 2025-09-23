package julius.game.chessengine.ai;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ParallelMateBroadcastTest {

    private AI ai;

    @BeforeEach
    void setUp() {
        ai = new AI(new Engine());
        ai.setSearchThreads(2);
        ai.setTimeLimit(Duration.ofSeconds(2).toMillis());
    }

    @Test
    void winningMateBroadcastStopsWorkers() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen("7k/6pp/8/8/8/8/6PP/5RK1 w - - 0 1");

        SearchTask task = new SearchTask(1L, engine.getBoardStateHash(), engine.whitesTurn(),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5), ai.getSearchThreads(), SearchInstrumentation.disabled());

        MoveAndScore result = ai.searchRootMoves(engine, task, 4,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, new SplittableRandom(1));

        assertNotNull(result, "Expected a winning mate move");

        Engine verify = engine.createSimulation();
        verify.performMove(result.getMove());
        assertTrue(verify.getGameState().isInStateCheckMate(), "Returned move must deliver mate");

        long childHash = verify.getBoardStateHash();
        TranspositionTableEntry entry = lookupMainEntry(ai, childHash);
        assertNotNull(entry, "Mate child should be cached");
        assertEquals(NodeType.EXACT, entry.nodeType);
        assertTrue(Math.abs(entry.score) >= Score.CHECKMATE - 50, "Mate entry should store mate score");
    }

    @Test
    void losingMateBroadcastRefutesRootMove() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen("6rk/5p2/8/6q1/2B5/8/7P/5R1K w - - 0 1");

        SearchTask task = new SearchTask(2L, engine.getBoardStateHash(), engine.whitesTurn(),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5), ai.getSearchThreads(), SearchInstrumentation.disabled());

        MoveAndScore result = ai.searchRootMoves(engine, task, 4,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, new SplittableRandom(3));

        assertNotNull(result, "Expected a best move even when facing mate threats");

        int losingMove = findMove(engine,
                MoveHelper.convertStringToIndex("c4"),
                MoveHelper.convertStringToIndex("f7"));
        assertNotEquals(losingMove, result.getMove(), "Losing mate move must be excluded from best choice");

        Engine losingLine = engine.createSimulation();
        losingLine.performMove(losingMove);
        long mateChildHash = losingLine.getBoardStateHash();
        TranspositionTableEntry entry = lookupMainEntry(ai, mateChildHash);
        assertNotNull(entry, "Refuted move child should be cached");
        assertEquals(NodeType.EXACT, entry.nodeType);
        assertTrue(entry.score <= -(Score.CHECKMATE - 50), "Refuted move must record losing mate score");

        int expectedSafeMove = findMove(engine,
                MoveHelper.convertStringToIndex("f1"),
                MoveHelper.convertStringToIndex("f2"));
        assertEquals(expectedSafeMove, result.getMove(), "Engine should choose the safe defence");
    }

    private static int findMove(Engine engine, int from, int to) {
        MoveList moves = engine.getAllLegalMoves();
        for (int i = 0; i < moves.size(); i++) {
            int mv = moves.getMove(i);
            if (MoveHelper.deriveFromIndex(mv) == from && MoveHelper.deriveToIndex(mv) == to) {
                return mv;
            }
        }
        fail("Move " + from + " -> " + to + " not legal in this position");
        return -1;
    }

    private static TranspositionTableEntry lookupMainEntry(AI ai, long hash) throws Exception {
        Field field = AI.class.getDeclaredField("transpositionTable");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        TranspositionTable<TranspositionTableEntry> table =
                (TranspositionTable<TranspositionTableEntry>) field.get(ai);
        return table.get(hash);
    }
}
