package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static julius.game.chessengine.board.BoardStateAssertions.assertBoardConsistent;
import static julius.game.chessengine.board.BoardStateAssertions.describeMove;
import static org.junit.jupiter.api.Assertions.*;

class AIMultiThreadConsistencyTest {

    private record Scenario(String label, String fen, int plies) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("Initial position", null, 6),
            new Scenario("Logged queen-capture position",
                    "8/8/4q1k1/4p1p1/5p2/7P/4K3/8 b - - 2 43", 8),
            new Scenario("Logged king-capture position",
                    "8/8/3q4/4pk2/5pP1/8/4K3/8 b - - 0 44", 8)
    );

    @Test
    void multiThreadedSearchAndPlayMaintainsBoardConsistency() throws InterruptedException {
        AiTuning tuning = AiTuning.builder()
                .searchThreads(2)
                .lazySmpThreads(4)
                .maxDepth(6)
                .timeLimitMillis(75)
                .build();

        Engine engine = new Engine();
        AI ai = new AI(engine, tuning);

        try {
            for (Scenario scenario : SCENARIOS) {
                runScenario(engine, ai, scenario);
            }
        } finally {
            ai.stopCalculation();
            ai.shutdown();
        }
    }

    private void runScenario(Engine engine, AI ai, Scenario scenario) throws InterruptedException {
        ai.stopCalculation();
        BitBoard.resetCaptureInconsistencyCounter();

        if (scenario.fen() == null) {
            engine.startNewGame();
        } else {
            engine.importBoardFromFen(scenario.fen());
        }

        String label = scenario.label();
        long baselineHash = engine.getBoardStateHash();
        assertBoardConsistent(engine.getBitBoard(), label + " (start)");

        ai.startAutoPlay(false, false);

        List<Integer> playedMoves = new ArrayList<>(scenario.plies());
        for (int ply = 0; ply < scenario.plies(); ply++) {
            if (engine.getGameState().isGameOver()) {
                break;
            }
            int move = waitForBestMove(ai, engine, label + " forward ply " + (ply + 1));
            if (move == -1) {
                break;
            }
            engine.performMove(move);
            playedMoves.add(move);
            assertBoardConsistent(engine.getBitBoard(), label + " after " + describeMove(move));
        }

        for (int i = playedMoves.size() - 1; i >= 0; i--) {
            int move = playedMoves.get(i);
            engine.undoLastMove();
            assertBoardConsistent(engine.getBitBoard(), label + " after undo " + describeMove(move));
        }

        ai.stopCalculation();

        assertEquals(baselineHash, engine.getBoardStateHash(),
                label + " board hash should return to baseline after undo sequence");
        assertEquals(0L, BitBoard.getCaptureInconsistencyCount(),
                label + " triggered capture inconsistencies during multi-threaded search");
        assertBoardConsistent(engine.getBitBoard(), label + " (restored)");
    }

    private int waitForBestMove(AI ai, Engine engine, String context) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (BitBoard.getCaptureInconsistencyCount() > 0) {
                fail(context + " observed capture inconsistency in worker threads");
            }
            if (engine.getGameState().isGameOver()) {
                return -1;
            }
            int move = ai.getCurrentBestMoveInt();
            if (move != -1) {
                return move;
            }
            Thread.sleep(10L);
        }
        fail(context + " timed out waiting for AI best move");
        return -1;
    }
}
