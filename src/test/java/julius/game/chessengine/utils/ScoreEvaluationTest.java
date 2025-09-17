package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.EvaluationPipeline;
import julius.game.chessengine.evaluation.KingSafetyModule;
import julius.game.chessengine.evaluation.PawnStructureModule;
import julius.game.chessengine.evaluation.KingSafetyModule.KingSafetyView;
import julius.game.chessengine.evaluation.PawnStructureModule.PawnStructureView;
import julius.game.chessengine.figures.PieceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScoreEvaluationTest {

    @Test
    void stalemateHasLowestMobility() {
        BitBoard stalemate = FEN.translateFENtoBitBoard("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        BitBoard active = FEN.translateFENtoBitBoard("7k/4Q3/6K1/8/8/8/8/8 b - - 0 1");

        int stalemateActivity = blendedModuleScore(new ActivityModule(), stalemate);
        int activeActivity = blendedModuleScore(new ActivityModule(), active);
        assertTrue(stalemateActivity > activeActivity);

        Score scoreStalemate = Score.initializeScore(stalemate);
        Score scoreActive = Score.initializeScore(active);
        assertTrue(scoreStalemate.getScoreDifference() > scoreActive.getScoreDifference());
    }

    @Test
    void exposedKingIsPenalized() {
        BitBoard safe = FEN.translateFENtoBitBoard("6k1/6pp/8/8/8/8/6PP/6K1 w - - 0 1");
        BitBoard exposed = FEN.translateFENtoBitBoard("6k1/8/8/6pp/8/8/6PP/6K1 w - - 0 1");

        KingSafetyView safeView = kingSafetyView(safe);
        KingSafetyView exposedView = kingSafetyView(exposed);
        int phase = safe.getPhase();
        assertTrue(exposedView.blackKing().blend(phase) < safeView.blackKing().blend(phase));

        Score scoreSafe = Score.initializeScore(safe);
        Score scoreExposed = Score.initializeScore(exposed);
        assertTrue(scoreExposed.getScoreDifference() > scoreSafe.getScoreDifference());
    }

    @Test
    void centerPawnsGrantBonusToWhite() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/4P3/8/8/4K3 w - - 0 1");
        PawnStructureView view = pawnStructure(board);
        assertEquals(PawnStructureModule.CENTER_PAWN_BONUS, view.whiteCenter().blend(board.getPhase()));
        assertEquals(0, view.blackCenter().blend(board.getPhase()));
    }

    @Test
    void centerPawnsGrantBonusToBlack() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/4p3/8/8/8/4K3 w - - 0 1");
        PawnStructureView view = pawnStructure(board);
        assertEquals(PawnStructureModule.CENTER_PAWN_BONUS, view.blackCenter().blend(board.getPhase()));
        assertEquals(0, view.whiteCenter().blend(board.getPhase()));
    }

    @Test
    void undoRestoresCapturedMaterial() {
        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/3p4/3P4/8/8/8 w - - 0 1");
        Score score = Score.initializeScore(board);

        int initialMidgame = score.getMidgameScore();
        int initialEndgame = score.getEndgameScore();

        int from = MoveHelper.convertStringToIndex("d4");
        int to = MoveHelper.convertStringToIndex("d5");
        int move = MoveHelper.createMoveInt(from, to, PieceType.PAWN, true, true,
                false, false, null, PieceType.PAWN, false, false, board.getLastMoveDoubleStepPawnIndex());

        board.performMove(move);
        score.applyMove(board, move, null);

        assertTrue(score.getMidgameScore() > initialMidgame);
        assertTrue(score.getEndgameScore() > initialEndgame);

        board.undoMove(move);
        score.undoMove(board, move, null);

        assertEquals(initialMidgame, score.getMidgameScore());
        assertEquals(initialEndgame, score.getEndgameScore());
    }

    private static int blendedModuleScore(EvaluationModule module, BitBoard board) {
        EvaluationPipeline pipeline = new EvaluationPipeline(java.util.List.of(module));
        pipeline.initialize(EvaluationContext.from(board, null));
        return pipeline.getBlendedScore();
    }

    private static KingSafetyView kingSafetyView(BitBoard board) {
        KingSafetyModule module = new KingSafetyModule();
        return module.getView(board);
    }

    private static PawnStructureView pawnStructure(BitBoard board) {
        PawnStructureModule module = new PawnStructureModule();
        return module.getView(board);
    }
}
