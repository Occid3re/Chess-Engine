package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.EngineTuningBootstrap;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AISyzygyIntegrationTest {

    @Test
    void alphaBetaReturnsExactScoreFromTablebase() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 w - - 0 1";
        engine.importBoardFromFen(fen);

        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(3), OptionalInt.empty(), Optional.empty());
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(fen, probe));

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            Method alphaBeta = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class, double.class,
                    double.class, boolean.class, long.class, int.class, int.class, int.class);
            alphaBeta.setAccessible(true);

            double score = (double) alphaBeta.invoke(ai, simulation, 1,
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                    simulation.whitesTurn(), Long.MAX_VALUE, -1, 0, 0);

            double expected = Score.tablebaseToEvaluation(TablebaseResult.from(probe), true);
            assertThat(score).isEqualTo(expected);

            ai.shutdown();
        }
    }

    @Test
    void determineTablebaseBestMovePicksWinningChild() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 w - - 0 1";
        engine.importBoardFromFen(fen);

        IntArrayList legalMoves = engine.getAllLegalMoves();
        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        responses.put(fen, new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(5), OptionalInt.empty(), Optional.empty()));

        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            engine.performMove(move);
            String childFen = FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();
            boolean forcedWin = childFen.contains("5QK1") || childFen.contains("4Q1K1");
            SyzygyWdl childResult = forcedWin ? SyzygyWdl.LOSS : SyzygyWdl.DRAW;
            responses.put(childFen, new SyzygyProbeResult(childResult, OptionalInt.of(1), OptionalInt.empty(), Optional.empty()));
            engine.undoLastMove();
        }

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            Method determine = AI.class.getDeclaredMethod("determineTablebaseBestMove", Engine.class, TablebaseResult.class,
                    boolean.class);
            determine.setAccessible(true);

            TablebaseResult parentResult = TablebaseResult.from(responses.get(fen));
            int bestMove = (int) determine.invoke(ai, simulation, parentResult, simulation.whitesTurn());

            Set<String> winningChildren = responses.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(fen))
                    .filter(entry -> entry.getValue().wdl() == SyzygyWdl.LOSS)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            assertThat(winningChildren)
                    .describedAs("expected at least one tablebase child to be marked as winning")
                    .isNotEmpty();

            Engine verifier = engine.createSimulation();
            verifier.performMove(bestMove);
            String bestChildFen = FEN.translateBoardToFEN(verifier.getBitBoard(), verifier.getGameState()).getRenderBoard();

            assertThat(winningChildren)
                    .describedAs("tablebase best move should correspond to a child scored as a forced win")
                    .contains(bestChildFen);

            ai.shutdown();
        }
    }

    @Test
    void determineTablebaseBestMoveHonoursConsistentRecommendation() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 w - - 0 1";
        engine.importBoardFromFen(fen);

        IntArrayList legalMoves = engine.getAllLegalMoves();
        Map<String, SyzygyProbeResult> responses = new HashMap<>();

        int recommendedMove = -1;
        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            engine.performMove(move);
            String childFen = FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();
            boolean forcedWin = childFen.contains("5QK1") || childFen.contains("4Q1K1");
            SyzygyWdl childResult = forcedWin ? SyzygyWdl.LOSS : SyzygyWdl.DRAW;
            responses.put(childFen, new SyzygyProbeResult(childResult, OptionalInt.of(1), OptionalInt.empty(), Optional.empty()));
            if (forcedWin && recommendedMove == -1) {
                recommendedMove = move;
            }
            engine.undoLastMove();
        }

        assertThat(recommendedMove)
                .describedAs("expected at least one forced win move to exist")
                .isNotEqualTo(-1);

        SyzygyMove recommendation = toSyzygyMove(recommendedMove);
        responses.put(fen, new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(5), OptionalInt.of(9), Optional.of(recommendation)));

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            Method determine = AI.class.getDeclaredMethod("determineTablebaseBestMove", Engine.class, TablebaseResult.class,
                    boolean.class);
            determine.setAccessible(true);

            TablebaseResult parentResult = TablebaseResult.from(responses.get(fen));
            int bestMove = (int) determine.invoke(ai, simulation, parentResult, simulation.whitesTurn());

            assertThat(bestMove).isEqualTo(recommendedMove);

            ai.shutdown();
        }
    }

    @Test
    void determineTablebaseBestMoveSkipsInconsistentRecommendation() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 w - - 0 1";
        engine.importBoardFromFen(fen);

        IntArrayList legalMoves = engine.getAllLegalMoves();
        Map<String, SyzygyProbeResult> responses = new HashMap<>();

        int winningMove = -1;
        int drawingMove = -1;
        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            engine.performMove(move);
            String childFen = FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();
            boolean forcedWin = childFen.contains("5QK1") || childFen.contains("4Q1K1");
            SyzygyWdl childResult = forcedWin ? SyzygyWdl.LOSS : SyzygyWdl.DRAW;
            responses.put(childFen, new SyzygyProbeResult(childResult, OptionalInt.of(1), OptionalInt.empty(), Optional.empty()));
            if (forcedWin && winningMove == -1) {
                winningMove = move;
            }
            if (!forcedWin && drawingMove == -1) {
                drawingMove = move;
            }
            engine.undoLastMove();
        }

        assertThat(winningMove)
                .describedAs("expected a winning continuation")
                .isNotEqualTo(-1);
        assertThat(drawingMove)
                .describedAs("expected a non-winning continuation to use as a bogus recommendation")
                .isNotEqualTo(-1);

        SyzygyMove recommendation = toSyzygyMove(drawingMove);
        responses.put(fen, new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(5), OptionalInt.of(9), Optional.of(recommendation)));

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            Method determine = AI.class.getDeclaredMethod("determineTablebaseBestMove", Engine.class, TablebaseResult.class,
                    boolean.class);
            determine.setAccessible(true);

            TablebaseResult parentResult = TablebaseResult.from(responses.get(fen));
            int bestMove = (int) determine.invoke(ai, simulation, parentResult, simulation.whitesTurn());

            assertThat(bestMove).isEqualTo(winningMove);

            ai.shutdown();
        }
    }

    @Test
    void resolveTablebaseHitReturnsWhitePerspectiveForBlackCaller() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 b - - 0 1";
        engine.importBoardFromFen(fen);

        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.LOSS, OptionalInt.of(3), OptionalInt.empty(), Optional.empty());
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(fen, probe));

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            Method resolver = AI.class.getDeclaredMethod("resolveTablebaseHit", Engine.class, boolean.class);
            resolver.setAccessible(true);

            Object raw = resolver.invoke(ai, simulation, false);
            assertThat(raw).isInstanceOf(Optional.class);

            Optional<?> hitOptional = (Optional<?>) raw;
            assertThat(hitOptional).isPresent();

            Object hit = hitOptional.orElseThrow();
            Method scoreAccessor = hit.getClass().getDeclaredMethod("score");
            scoreAccessor.setAccessible(true);
            double score = (double) scoreAccessor.invoke(hit);

            double expected = Score.tablebaseToEvaluation(TablebaseResult.from(probe), simulation.whitesTurn(),
                    simulation.getGameState().getHalfmoveClock());

            assertThat(simulation.whitesTurn()).isFalse();
            assertThat(score).isEqualTo(expected);
            assertThat(score).isPositive();

            ai.shutdown();
        }
    }

    @Test
    void evaluateTablebaseChildReturnsWhitePerspectiveForBlackParent() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 b - - 0 1";
        engine.importBoardFromFen(fen);

        IntArrayList legalMoves = engine.getAllLegalMoves();
        assertThat(legalMoves.size()).isGreaterThan(0);

        int targetMove = legalMoves.getInt(0);
        engine.performMove(targetMove);
        String childFen = FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();
        engine.undoLastMove();

        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        responses.put(fen, new SyzygyProbeResult(SyzygyWdl.LOSS, OptionalInt.of(5), OptionalInt.empty(), Optional.empty()));
        SyzygyProbeResult childProbe = new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(7), OptionalInt.empty(), Optional.empty());
        responses.put(childFen, childProbe);

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            simulation.performMove(targetMove);

            try {
                Method evaluator = AI.class.getDeclaredMethod("evaluateTablebaseChild", Engine.class, boolean.class);
                evaluator.setAccessible(true);

                double eval = (double) evaluator.invoke(ai, simulation, false);

                TablebaseResult expectedResult = TablebaseResult.from(childProbe);
                double expected = Score.tablebaseToEvaluation(expectedResult, simulation.whitesTurn(),
                        simulation.getGameState().getHalfmoveClock());

                assertThat(simulation.whitesTurn()).isTrue();
                assertThat(eval).isEqualTo(expected);
            } finally {
                simulation.undoLastMove();
                ai.shutdown();
            }
        }
    }

    @Test
    void tablebaseTieBreakKeepsUnprobedIncumbentWhenCandidateLoses() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 w - - 0 1";
        engine.importBoardFromFen(fen);

        IntArrayList legalMoves = engine.getAllLegalMoves();
        assertThat(legalMoves.size()).isGreaterThan(1);

        int incumbentMove = legalMoves.getInt(0);
        int losingMove = legalMoves.getInt(1);

        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        engine.performMove(losingMove);
        String losingFen = FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();
        engine.undoLastMove();
        responses.put(losingFen, new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(7), OptionalInt.empty(), Optional.empty()));

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            Method prefer = AI.class.getDeclaredMethod("preferCandidateByTablebase", Engine.class, int.class, double.class,
                    boolean.class, int.class, boolean.class);
            prefer.setAccessible(true);

            double incumbentEval = -0.25;
            double losingEval = -0.25;
            boolean losingZeroing = MoveHelper.isCapture(losingMove)
                    || MoveHelper.derivePieceTypeBits(losingMove) == 1;
            boolean incumbentZeroing = MoveHelper.isCapture(incumbentMove)
                    || MoveHelper.derivePieceTypeBits(incumbentMove) == 1;

            boolean preferLosing = (boolean) prefer.invoke(ai, simulation, losingMove, losingEval, losingZeroing,
                    incumbentMove, incumbentZeroing);

            assertThat(preferLosing).isFalse();

            ai.shutdown();
        }
    }

    private AutoCloseable overrideScoreTablebase(TestSyzygyTablebaseService service) {
        SyzygyTablebaseService previous = Score.getTablebaseService();
        Score.setTablebaseService(service);
        return () -> {
            if (previous == null) {
                Score.clearTablebaseService();
            } else {
                Score.setTablebaseService(previous);
            }
        };
    }

    private SyzygyMove toSyzygyMove(int move) {
        return new SyzygyMove(MoveHelper.deriveFromIndex(move),
                MoveHelper.deriveToIndex(move),
                MoveHelper.derivePromotionPieceTypeBits(move));
    }
}
