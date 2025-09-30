package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.tuning.AiTuning;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.DeterministicAiHelper;
import testsupport.TestLoggingExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
@ExtendWith(TestLoggingExtension.class)
class AITest_PruningAndReductions {

    @Test
    @DisplayName("LMR reduction is bounded and monotonic across depth and move index")
    void lmrReductionMonotonic() throws Exception {
        AI ai = new AI(new Engine(), AiTuning.defaults());
        Method lmr = AI.class.getDeclaredMethod("lmrReduction", int.class, int.class, int.class);
        lmr.setAccessible(true);

        int previousDepthReduction = 0;
        for (int depth = 2; depth <= 6; depth++) {
            int previousMoveReduction = 0;
            for (int moveIndex = 0; moveIndex < 8; moveIndex++) {
                int reduction = (int) lmr.invoke(ai, depth, moveIndex, 1500);
                log.info("LMR(depth={}, moveIndex={}) -> {}", depth, moveIndex, reduction);
                assertTrue(reduction >= 0, "Reduction must be non-negative");
                assertTrue(reduction <= depth - 1, "Reduction must be < depth");
                assertTrue(reduction >= previousMoveReduction,
                        "Later moves should not decrease reduction compared to earlier ones");
                previousMoveReduction = reduction;
            }
            assertTrue(previousMoveReduction >= previousDepthReduction,
                    "Higher depths should not reduce the maximum reduction achieved at lower depths");
            previousDepthReduction = previousMoveReduction;
        }
    }

    @Test
    @DisplayName("SEE pruning skips losing captures that fail to give check")
    void seePruningDropsLosingCapture() throws Exception {
        PrunableEngine engine = new PrunableEngine();
        engine.importBoardFromFen("4k3/4p3/8/3p4/1pp2p2/p1R1p3/8/4K3 w - - 0 1");

        AI ai = new AI(engine, AiTuning.defaults());
        try (AutoCloseable _ = DeterministicAiHelper.withSingleThread(ai)) {
            PrunableEngine simulation = (PrunableEngine) engine.createSimulation();
            IntArrayList legal = simulation.getAllLegalMoves();
            assertFalse(legal.isEmpty(), "Test position should have legal moves");
            simulation.markLosingCapture(legal);

            ai.evaluateBoard(simulation, true,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000));

            List<String> movesPerformed = simulation.performedMoves.stream()
                    .map(julius.game.chessengine.board.Move::convertIntToMove)
                    .map(Move::toString)
                    .toList();
            log.info("Performed moves recorded: {}", movesPerformed);
            assertEquals(0, simulation.losingCapturePerformed,
                    "Losing capture without giving check should be pruned");
        }
    }

    @Test
    @DisplayName("Null-move pruning disabled when side to move is in check")
    void nullMoveDisabledInCheck() throws Exception {
        NullTrackingEngine inCheck = new NullTrackingEngine();
        inCheck.importBoardFromFen("4k3/8/8/8/8/8/4R3/4K3 b - - 0 1");
        inCheck.getGameState().setState(GameStateEnum.BLACK_IN_CHECK);

        NullTrackingEngine safe = new NullTrackingEngine();
        safe.importBoardFromFen("4k3/8/8/8/8/8/4R3/4K3 w - - 0 1");
        safe.getGameState().setState(GameStateEnum.PLAY);

        AI aiInCheck = new AI(inCheck, AiTuning.defaults());
        AI aiSafe = new AI(safe, AiTuning.defaults());

        Method alphaBeta = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class, double.class, double.class,
                boolean.class, long.class, int.class, int.class, int.class);
        alphaBeta.setAccessible(true);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        alphaBeta.invoke(aiInCheck, inCheck.createSimulation(), 3,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, deadline, 1, 0, 0);
        alphaBeta.invoke(aiSafe, safe.createSimulation(), 3,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, true, deadline, 1, 0, 0);

        log.info("Null-move counters -> inCheck={}, safe={}", inCheck.nullMoveCalls, safe.nullMoveCalls);
        assertEquals(0, inCheck.nullMoveCalls,
                "Engine in check must not attempt null-move pruning");
        assertTrue(safe.nullMoveCalls > 0,
                "Safe position should attempt at least one null move");
    }

    private static class PrunableEngine extends Engine {
        private int losingCapture = -1;
        private int losingCapturePerformed;
        private final List<Integer> performedMoves = new ArrayList<>();

        PrunableEngine() {
            super();
        }

        PrunableEngine(Engine other) {
            super(other);
        }

        @Override
        public Engine createSimulation() {
            return new PrunableEngine(this);
        }

        void markLosingCapture(IntArrayList legal) {
            int sideSign = whitesTurn() ? +1 : -1;
            for (int i = 0; i < legal.size(); i++) {
                int move = legal.getInt(i);
                if (!MoveHelper.isCapture(move)) continue;

                // Try it: does it give check? If yes, skip (we want the prune path).
                performMove(move);
                boolean givesCheck = getGameState().getState() ==
                        (whitesTurn() ? GameStateEnum.WHITE_IN_CHECK : GameStateEnum.BLACK_IN_CHECK);
                undoLastMove();

                if (!givesCheck) {
                    losingCapture = move;
                    return;
                }
            }
            throw new IllegalStateException("No non-checking capture available to mark as losing");
        }


        @Override
        public int see(int move) {
            if (move == losingCapture) {
                return -200;
            }
            return super.see(move);
        }

        @Override
        public void performMove(int move) {
            performedMoves.add(move);
            if (move == losingCapture) {
                losingCapturePerformed++;
            }
            super.performMove(move);
        }
    }

    private static class NullTrackingEngine extends Engine {
        private int nullMoveCalls;

        NullTrackingEngine() {
            super();
        }

        NullTrackingEngine(Engine other) {
            super(other);
        }

        @Override
        public Engine createSimulation() {
            return new NullTrackingEngine(this);
        }

        @Override
        public int doNullMoveForSearch() {
            nullMoveCalls++;
            return super.doNullMoveForSearch();
        }
    }
}

