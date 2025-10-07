package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.utils.Score;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.DeterministicAiHelper;
import testsupport.TestLoggingExtension;
import testsupport.TestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
@ExtendWith(TestLoggingExtension.class)
class AITest_QuiescenceAndTerminalInvariants {

    @Test
    @DisplayName("evaluateBoard returns plain DRAW for terminal draws (no bias)")
    void evaluateBoardRespectsDrawBias() throws Exception {
        try (AutoCloseable ignored = Score.useFactory(bitBoard -> new Score(bitBoard, null) {
            @Override
            public double getScoreDifference() {
                return 150.0;
            }
        })) {
            CountingEngine engine = new CountingEngine();
            engine.getGameState().setState(GameStateEnum.DRAW);
            engine.getGameState().setDrawByInsufficientMaterial(false);

            AI ai = new AI(engine, AiTuning.defaults());
            try (AutoCloseable threads = DeterministicAiHelper.withSingleThread(ai)) {
                double whiteScore = ai.evaluateBoard(engine.createSimulation(), true,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));
                double blackScore = ai.evaluateBoard(engine.createSimulation(), false,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));

                log.info("Draw bias check -> whiteScore={}, blackScore={} ", whiteScore, blackScore);

                assertEquals(Score.DRAW, whiteScore, 1e-6,
                        "Terminal draw currently returns plain DRAW (no bias)");
                assertEquals(Score.DRAW, blackScore, 1e-6,
                        "Terminal draw currently returns plain DRAW (no bias)");
            }
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("Quiescence expands more evasions when side is in check")
    void quiescenceExploresMoreMovesWhenInCheck() throws Exception {
        CountingEngine inCheckRoot = new CountingEngine();
        inCheckRoot.importBoardFromFen("4k3/8/8/8/8/8/4R3/4K3 b - - 0 1");

        CountingEngine safeRoot = new CountingEngine();
        safeRoot.importBoardFromFen("4k3/4p3/8/8/8/8/4R3/4K3 b - - 0 1");

        AI ai = new AI(inCheckRoot, AiTuning.defaults());
        try (AutoCloseable threads = DeterministicAiHelper.withSingleThread(ai);
             AutoCloseable time = DeterministicAiHelper.withShortTimeLimit(ai, 150)) {
            CountingEngine inCheckSim = (CountingEngine) inCheckRoot.createSimulation();
            ai.evaluateBoard(inCheckSim, false,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));

            CountingEngine safeSim = (CountingEngine) safeRoot.createSimulation();
            // swap main engine reference so SEE cache and TT remain valid for the safe position
            TestUtils.writeField(ai, "mainEngine", safeRoot);
            ai.evaluateBoard(safeSim, false,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));

            log.info("In-check quiescence performed {} moves, safe position performed {}", inCheckSim.getPerformCount(),
                    safeSim.getPerformCount());

            assertTrue(inCheckSim.getPerformCount() > safeSim.getPerformCount(),
                    "In-check position should explore at least as many moves as the safe position");
        }
    }

    @Test
    @DisplayName("Quiescence move ordering ranks promotions and winning captures first")
    void quiescenceOrderingPrefersPromotionsAndWinningCaptures() {
        StaticSeeEngine engine = new StaticSeeEngine();
        AI ai = new AI(engine, AiTuning.defaults());

        int promotion = MoveHelper.createMoveInt(
                MoveHelper.convertStringToIndex("a7"),
                MoveHelper.convertStringToIndex("a8"),
                PieceType.PAWN,
                true,
                false,
                false,
                false,
                PieceType.QUEEN,
                null,
                false,
                false,
                0
        );

        int goodCapture = MoveHelper.createMoveInt(
                MoveHelper.convertStringToIndex("h1"),
                MoveHelper.convertStringToIndex("h8"),
                PieceType.ROOK,
                true,
                true,
                false,
                false,
                null,
                PieceType.QUEEN,
                false,
                false,
                0
        );

        int equalCapture = MoveHelper.createMoveInt(
                MoveHelper.convertStringToIndex("b1"),
                MoveHelper.convertStringToIndex("b8"),
                PieceType.ROOK,
                true,
                true,
                false,
                false,
                null,
                PieceType.ROOK,
                false,
                false,
                0
        );

        int badCapture = MoveHelper.createMoveInt(
                MoveHelper.convertStringToIndex("c1"),
                MoveHelper.convertStringToIndex("c8"),
                PieceType.BISHOP,
                true,
                true,
                false,
                false,
                null,
                PieceType.PAWN,
                false,
                false,
                0
        );

        engine.setSeeScore(goodCapture, 400);
        engine.setSeeScore(equalCapture, 0);
        engine.setSeeScore(badCapture, -250);

        IntArrayList moves = new IntArrayList(new int[]{badCapture, equalCapture, promotion, goodCapture});

        ai.orderQuiescenceMoves(moves, engine);

        assertEquals(promotion, moves.getInt(0), "Promotions should be explored before captures");
        assertEquals(goodCapture, moves.getInt(1), "Winning captures should follow promotions");
        assertEquals(equalCapture, moves.getInt(2), "Neutral captures should precede losing captures");
        assertEquals(badCapture, moves.getInt(3), "Losing captures should be delayed");

        ai.shutdown();
    }

    private static class CountingEngine extends Engine {
        private int performCount;

        CountingEngine() {
            super();
        }

        CountingEngine(Engine other) {
            super(other);
        }

        @Override
        public Engine createSimulation() {
            return new CountingEngine(this);
        }

        @Override
        public void performMove(int move) {
            performCount++;
            super.performMove(move);
        }

        int getPerformCount() {
            return performCount;
        }
    }

    private static class StaticSeeEngine extends Engine {
        private final Map<Integer, Integer> seeScores = new HashMap<>();

        StaticSeeEngine() {
            super();
        }

        @Override
        public int see(int move) {
            return seeScores.getOrDefault(move, 0);
        }

        void setSeeScore(int move, int score) {
            seeScores.put(move, score);
        }
    }
}

