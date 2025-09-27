package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SortMovesByEfficiencyTest {

    private static final String SEE_ORDER_FEN = "r2q1rk1/ppp2ppp/8/2b1p3/2B1P3/3P1N2/PPP2PPP/R1BQ1RK1 w - - 0 1";
    private static final String TACTICAL_FEN = "5rkr/pp2Rp2/1b1p1Pb1/3P2Q1/2n3P1/2p5/P4P2/4R1K1 w - - 1 0";

    private static final class CountingEngine extends Engine {
        private int seeCalls;

        @Override
        public int see(int move) {
            seeCalls++;
            return super.see(move);
        }

        int getSeeCalls() {
            return seeCalls;
        }
    }

    @Test
    void positiveSeeCapturesArePrioritisedAndLosingTradesAreDeferred() {
        CountingEngine engine = new CountingEngine();
        engine.importBoardFromFen(SEE_ORDER_FEN);

        Engine evaluationEngine = new Engine();
        evaluationEngine.importBoardFromFen(SEE_ORDER_FEN);

        AI ai = new AI(engine);

        IntArrayList moves = engine.getAllLegalMoves();
        long hash = engine.getBoardStateHash();

        IntArrayList ordered = ai.sortMovesByEfficiency(moves, 0, hash, -1, engine);

        Set<Integer> captureMoves = new HashSet<>();
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (MoveHelper.isCapture(move)) {
                captureMoves.add(move);
            }
        }
        assertFalse(captureMoves.isEmpty(), "Position should expose capture moves");
        assertEquals(captureMoves.size(), engine.getSeeCalls(), "SEE results must be cached per capture");

        Map<Integer, Integer> seeByMove = new HashMap<>();
        for (int move : captureMoves) {
            seeByMove.put(move, evaluationEngine.see(move));
        }

        assertTrue(seeByMove.values().stream().anyMatch(v -> v > 0), "Test FEN should contain a winning capture");
        assertTrue(seeByMove.values().stream().anyMatch(v -> v < 0), "Test FEN should contain a losing capture");

        int bestSee = seeByMove.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        int worstSee = seeByMove.values().stream().mapToInt(Integer::intValue).min().orElseThrow();

        int firstCaptureIndex = -1;
        int firstCaptureMove = -1;
        for (int i = 0; i < ordered.size(); i++) {
            int move = ordered.getInt(i);
            if (seeByMove.containsKey(move)) {
                firstCaptureIndex = i;
                firstCaptureMove = move;
                break;
            }
        }
        assertTrue(firstCaptureIndex >= 0, "Ordered list should contain capture moves");
        assertEquals(bestSee, seeByMove.get(firstCaptureMove), "Winning SEE capture must be first");

        int worstCaptureIndex = -1;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            int move = ordered.getMove(i);
            if (seeByMove.containsKey(move) && seeByMove.get(move) == worstSee) {
                worstCaptureIndex = i;
                break;
            }
        }
        assertTrue(worstCaptureIndex > firstCaptureIndex, "Losing captures should be pushed back");
    }


    @SuppressWarnings("unchecked")
    private static TranspositionTable<TranspositionTableEntry> extractTranspositionTable(AI ai) throws Exception {
        Field field = AI.class.getDeclaredField("transpositionTable");
        field.setAccessible(true);
        return (TranspositionTable<TranspositionTableEntry>) field.get(ai);
    }
}
