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
    @DisplayName("SEE pruning skips (or at most probes) a losing capture that does NOT give check")
    void seePruningDropsLosingCapture() throws Exception {
        // Position with multiple captures; we’ll tag one as “losing” and ensure the q-search
        // does not actually pursue it beyond a possible single probe.
        PrunableEngine engine = new PrunableEngine();
        engine.importBoardFromFen("4k3/4p3/8/3p4/1pp2p2/p1R1p3/8/4K3 w - - 0 1");

        AI ai = new AI(engine, AiTuning.defaults());
        try (AutoCloseable _ = DeterministicAiHelper.withSingleThread(ai)) {
            PrunableEngine sim = (PrunableEngine) engine.createSimulation();

            IntArrayList legal = sim.getAllLegalMoves();
            assertFalse(legal.isEmpty(), "Test position should have legal moves");
            sim.markLosingNonCheckingCapture(legal);

            assertTrue(sim.losingCapture != -1, "We must have found a losing, non-checking capture to test");
            String losingMoveStr = Move.convertIntToMove(sim.losingCapture).toString();

            ai.evaluateBoard(sim, true, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000));

            List<String> movesPerformed = sim.performedMoves.stream()
                    .map(julius.game.chessengine.board.Move::convertIntToMove)
                    .map(Move::toString)
                    .toList();
            log.info("Performed moves (in-order): {}", movesPerformed);
            log.info("Losing non-checking capture chosen for test: {}", losingMoveStr);
            log.info("Count of losing-capture performs: {}", sim.losingCapturePerformed);

            // Current engine logic may do a *single* make/undo probe to see if the move gives check.
            // We assert that it's never pursued beyond that.
            assertTrue(sim.losingCapturePerformed <= 1,
                    () -> "Losing capture without giving check should be pruned; "
                            + "0 is ideal; 1 is allowed for a probe. Found: " + sim.losingCapturePerformed
                            + " | moves: " + movesPerformed);
        }
    }

    @Test
    @DisplayName("Null-move pruning is disabled in check and enabled in a safe, non-endgame, finite-window node")
    void nullMoveDisabledInCheck() throws Exception {
        // In-check case
        NullTrackingEngine inCheck = new NullTrackingEngine();
        inCheck.importBoardFromFen("4k3/8/8/8/8/8/4R3/4K3 b - - 0 1");
        inCheck.getGameState().setState(GameStateEnum.BLACK_IN_CHECK);

        // Safe, non-endgame position (no check, plenty of material)
        NullTrackingEngine safe = new NullTrackingEngine();
        safe.importBoardFromFen("rnbqkbnr/pppppppp/8/8/3P4/5N2/PPP1PPPP/RNBQKB1R b KQkq - 1 2");
        safe.getGameState().setState(GameStateEnum.PLAY);

        AI aiInCheck = new AI(inCheck, AiTuning.defaults());
        AI aiSafe   = new AI(safe,   AiTuning.defaults());

        Method alphaBeta = AI.class.getDeclaredMethod("alphaBeta",
                Engine.class, int.class, double.class, double.class,
                boolean.class, long.class, int.class, int.class, int.class);
        alphaBeta.setAccessible(true);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        // Use simulations and assert on the simulation counters
        NullTrackingEngine simInCheck = (NullTrackingEngine) inCheck.createSimulation();
        NullTrackingEngine simSafe    = (NullTrackingEngine) safe.createSimulation();

        // 1) In-check path: infinite window is fine (we expect 0 null moves)
        alphaBeta.invoke(aiInCheck, simInCheck, 3,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                /*isWhite*/ false, deadline, /*prevMove*/ 1, /*ply*/ 0, /*extStreak*/ 0);

        // 2) Safe path: finite window so mate-threat guard doesn’t disable it
        double alpha = -200, beta = 200;
        // side to move is black in the FEN above, so isWhite=false
        alphaBeta.invoke(aiSafe, simSafe, 4,
                alpha, beta,
                /*isWhite*/ false, deadline, /*prevMove*/ 1, /*ply*/ 0, /*extStreak*/ 0);

        log.info("Null-move calls -> inCheck={}, safe={}", simInCheck.nullMoveCalls, simSafe.nullMoveCalls);
        assertEquals(0, simInCheck.nullMoveCalls,
                "When in check, null-move pruning must not be attempted");
        assertTrue(simSafe.nullMoveCalls > 0,
                "In a safe, non-endgame position with a finite window, the engine should attempt at least one null move");
    }


    // ---------------------------------------------------------------------
    // Test doubles
    // ---------------------------------------------------------------------

    /**
     * Engine that allows us to designate exactly one capture as:
     *  - losing by SEE (negative),
     *  - and *not* giving check,
     * while counting how many times it was actually performed.
     */
    static class PrunableEngine extends Engine {
        int losingCapture = -1;
        int losingCapturePerformed;
        final List<Integer> performedMoves = new ArrayList<>();

        PrunableEngine() { super(); }
        PrunableEngine(Engine other) { super(other); }

        @Override
        public Engine createSimulation() { return new PrunableEngine(this); }

        void markLosingNonCheckingCapture(IntArrayList legal) {
            for (int i = 0; i < legal.size(); i++) {
                int move = legal.getInt(i);
                if (!MoveHelper.isCapture(move)) continue;

                // Try the move: we want a *non-checking* capture.
                performMove(move);

                boolean terminalNow = getGameState().isTerminal() || getGameState().isInStateCheckMate();
                boolean opponentInCheck =
                        getGameState().getState() ==
                                (whitesTurn() ? GameStateEnum.WHITE_IN_CHECK : GameStateEnum.BLACK_IN_CHECK);

                undoLastMove();

                if (terminalNow || opponentInCheck) {
                    // Skip: we only want "non-checking" captures for this test
                    continue;
                }

                // Great — tag this as the losing capture by SEE
                losingCapture = move;
                log.info("Selected losing non-checking capture for test: {}", Move.convertIntToMove(move));
                return;
            }
            throw new IllegalStateException("No non-checking capture available to mark as losing");
        }

        @Override
        public int see(int move) {
            if (move == losingCapture) return -200; // clearly losing
            return super.see(move);
        }

        @Override
        public void performMove(int move) {
            performedMoves.add(move);
            if (move == losingCapture) losingCapturePerformed++;
            super.performMove(move);
        }
    }

    static class NullTrackingEngine extends Engine {
        int nullMoveCalls;

        NullTrackingEngine() { super(); }
        NullTrackingEngine(Engine other) { super(other); }

        @Override
        public Engine createSimulation() { return new NullTrackingEngine(this); }

        @Override
        public int doNullMoveForSearch() {
            nullMoveCalls++;
            return super.doNullMoveForSearch();
        }
    }
}
