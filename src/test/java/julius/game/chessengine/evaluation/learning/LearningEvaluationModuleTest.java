package julius.game.chessengine.evaluation.learning;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.MoveContext;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.tuning.EngineTuningBootstrap;
import julius.game.chessengine.tuning.ParamId;
import julius.game.chessengine.tuning.ParameterRegistry;
import julius.game.chessengine.utils.Score;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LearningEvaluationModuleTest {

    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    void deterministicInferenceWithFixedWeights() {
        LearningModelStore store = buildLinearStore(120.0, 80.0);
        LearningEvaluationModule module = new LearningEvaluationModule(store);

        BitBoard board = FEN.translateFENtoBitBoard(START_FEN);
        applyMoves(board,
                MoveHelper.createMoveInt(index("e2"), index("e4"), PieceType.PAWN, true, false,
                        false, false, null, null, false, false, packCastlingState(board)),
                MoveHelper.createMoveInt(index("d7"), index("d5"), PieceType.PAWN, false, false,
                        false, false, null, null, false, false, packCastlingState(board)),
                MoveHelper.createMoveInt(index("e4"), index("d5"), PieceType.PAWN, true, true,
                        false, false, null, PieceType.PAWN, false, false, packCastlingState(board)));

        EvaluationContext context = EvaluationContext.from(board, null);
        module.initialize(context);
        module.evaluate(context);

        double pawnFeature = pawnFeature(context);
        int expectedMid = (int) Math.round(pawnFeature * 120.0);
        int expectedEnd = (int) Math.round(pawnFeature * 80.0);

        assertThat(module.getMidgameScore()).isEqualTo(expectedMid);
        assertThat(module.getEndgameScore()).isEqualTo(expectedEnd);
    }

    @Test
    void applyAndUndoMoveStayInSyncWithRebuilds() {
        LearningModelStore store = buildLinearStore(150.0, 90.0);
        LearningEvaluationModule module = new LearningEvaluationModule(store);

        BitBoard board = FEN.translateFENtoBitBoard(START_FEN);
        EvaluationContext initialContext = EvaluationContext.from(board, null);
        module.initialize(initialContext);
        module.evaluate(initialContext);
        int initialMid = module.getMidgameScore();
        int initialEnd = module.getEndgameScore();

        int move = MoveHelper.createMoveInt(index("e2"), index("e4"), PieceType.PAWN, true, false,
                false, false, null, null, false, false, packCastlingState(board));
        board.performMove(move);
        EvaluationContext forwardContext = EvaluationContext.from(board, null);
        MoveContext forward = new MoveContext(move, initialContext, forwardContext);
        module.applyMove(forward);
        module.evaluate(forwardContext);

        LearningEvaluationModule reference = new LearningEvaluationModule(store);
        reference.initialize(forwardContext);
        reference.evaluate(forwardContext);

        assertThat(module.getMidgameScore()).isEqualTo(reference.getMidgameScore());
        assertThat(module.getEndgameScore()).isEqualTo(reference.getEndgameScore());

        board.undoMove(move);
        EvaluationContext undoContext = EvaluationContext.from(board, null);
        MoveContext backward = new MoveContext(move, forwardContext, undoContext);
        module.undoMove(backward);
        module.evaluate(undoContext);

        assertThat(module.getMidgameScore()).isEqualTo(initialMid);
        assertThat(module.getEndgameScore()).isEqualTo(initialEnd);

        LearningEvaluationModule rebuild = new LearningEvaluationModule(store);
        rebuild.initialize(undoContext);
        rebuild.evaluate(undoContext);

        assertThat(module.getMidgameScore()).isEqualTo(rebuild.getMidgameScore());
        assertThat(module.getEndgameScore()).isEqualTo(rebuild.getEndgameScore());
    }

    @Test
    void defaultDefinitionProducesNonZeroScoresForCommonPositions() {
        LearningEvaluationModule module = new LearningEvaluationModule();

        List<String> representativeFens = List.of(
                "8/8/2k5/8/4K3/6P1/8/8 w - - 0 1",
                "4k3/8/8/8/8/8/8/3K4 w - - 0 1"
        );
        for (String fen : representativeFens) {
            BitBoard board = FEN.translateFENtoBitBoard(fen);
            EvaluationContext context = EvaluationContext.from(board, null);
            module.initialize(context);
            module.evaluate(context);
            int midgameScore = module.getMidgameScore();
            int endgameScore = module.getEndgameScore();
            assertThat(Math.abs(midgameScore) + Math.abs(endgameScore)).as("Scaled output for FEN %s", fen)
                    .isNotZero();
        }
    }

    @Test
    void outputScalesRespondToParameterOverrides() throws Exception {
        EngineTuningBootstrap.ensureDefaultTuning();
        LearningEvaluationModule module = new LearningEvaluationModule();
        BitBoard board = FEN.translateFENtoBitBoard("8/8/2k5/8/4K3/6P1/8/8 w - - 0 1");
        EvaluationContext context = EvaluationContext.from(board, null);
        module.initialize(context);
        module.evaluate(context);

        int baselineMid = module.getMidgameScore();
        int baselineEnd = module.getEndgameScore();
        assertThat(baselineMid).isNotZero();
        assertThat(baselineEnd).isNotZero();

        double baselineMidScale = ParameterRegistry.get(ParamId.LEARNING_OUTPUT_MIDGAME_SCALE);
        double baselineEndScale = ParameterRegistry.get(ParamId.LEARNING_OUTPUT_ENDGAME_SCALE);
        double boostedMidScale = baselineMidScale * 1.5;
        double reducedEndScale = baselineEndScale * 0.5;

        try (AutoCloseable ignored = Score.useNumericParameters(Map.of(
                "learning.outputMidgameScale", boostedMidScale,
                "learning.outputEndgameScale", reducedEndScale
        ))) {
            module.markDirty();
            module.evaluate(context);
            int adjustedMid = module.getMidgameScore();
            int adjustedEnd = module.getEndgameScore();

            assertThat(adjustedMid).isNotEqualTo(baselineMid);
            assertThat(adjustedEnd).isNotEqualTo(baselineEnd);

            double midFactor = adjustedMid / (double) baselineMid;
            double endFactor = adjustedEnd / (double) baselineEnd;
            double expectedMidFactor = boostedMidScale / baselineMidScale;
            double expectedEndFactor = reducedEndScale / baselineEndScale;

            assertThat(midFactor).isCloseTo(expectedMidFactor, within(0.2));
            assertThat(endFactor).isCloseTo(expectedEndFactor, within(0.2));
        }

        module.markDirty();
        module.evaluate(context);
        assertThat(module.getMidgameScore()).isEqualTo(baselineMid);
        assertThat(module.getEndgameScore()).isEqualTo(baselineEnd);
    }

    private static LearningModelStore buildLinearStore(double midgameScale, double endgameScale) {
        double[][] weights = new double[2][LearningEvaluationModule.FEATURE_VECTOR_SIZE];
        weights[0][0] = 1.0;
        weights[1][0] = 1.0;
        double[] bias = new double[] {0.0, 0.0};
        double[] scales = new double[] {midgameScale, endgameScale};
        LearningModelStore.LayerDefinition layer =
                new LearningModelStore.LayerDefinition(weights, bias, "LINEAR");
        LearningModelStore.Definition definition =
                new LearningModelStore.Definition(LearningEvaluationModule.FEATURE_VECTOR_SIZE,
                        List.of(layer), scales);
        return new LearningModelStore(definition);
    }

    private static void applyMoves(BitBoard board, int... moves) {
        for (int move : moves) {
            board.performMove(move);
        }
    }

    private static int index(String square) {
        return MoveHelper.convertStringToIndex(square);
    }

    private static double pawnFeature(EvaluationContext context) {
        int white = Long.bitCount(context.getWhitePawns());
        int black = Long.bitCount(context.getBlackPawns());
        return (white - black) / 8.0;
    }

    private static int packCastlingState(BitBoard board) {
        int state = 0;
        if (board.isWhiteKingMoved()) state |= 0x01;
        if (board.isWhiteRookA1Moved()) state |= 0x02;
        if (board.isWhiteRookH1Moved()) state |= 0x04;
        if (board.isBlackKingMoved()) state |= 0x08;
        if (board.isBlackRookA8Moved()) state |= 0x10;
        if (board.isBlackRookH8Moved()) state |= 0x20;
        return state;
    }
}
