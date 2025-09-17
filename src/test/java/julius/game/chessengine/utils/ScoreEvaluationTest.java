package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.EvaluationPipeline;
import julius.game.chessengine.evaluation.KingSafetyModule;
import julius.game.chessengine.evaluation.MaterialModule;
import julius.game.chessengine.evaluation.PawnStructureModule;
import julius.game.chessengine.evaluation.PieceSquareModule;
import julius.game.chessengine.evaluation.KingSafetyModule.KingSafetyView;
import julius.game.chessengine.evaluation.PawnStructureModule.PawnStructureView;
import julius.game.chessengine.figures.PieceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
    void passedPawnImprovesScore() {
        BitBoard passed = FEN.translateFENtoBitBoard("4k3/8/8/4P3/8/8/8/4K3 w - - 0 1");
        BitBoard blocked = FEN.translateFENtoBitBoard("4k3/4p3/8/4P3/8/8/8/4K3 w - - 0 1");

        Score passedScore = Score.initializeScore(passed);
        Score blockedScore = Score.initializeScore(blocked);
        assertTrue(passedScore.getScoreDifference() > blockedScore.getScoreDifference());
    }

    @Test
    void doubledPawnsReduceScore() {
        BitBoard healthy = FEN.translateFENtoBitBoard("4k3/8/8/8/3P4/8/4P3/4K3 w - - 0 1");
        BitBoard doubled = FEN.translateFENtoBitBoard("4k3/8/8/8/3P4/8/3P4/4K3 w - - 0 1");

        Score healthyScore = Score.initializeScore(healthy);
        Score doubledScore = Score.initializeScore(doubled);
        assertTrue(healthyScore.getScoreDifference() > doubledScore.getScoreDifference());
    }

    @Test
    void deliveringCheckAddsBonusForAttacker() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/4Q3/8/8/8/8/8/4K3 b - - 0 1");

        ScoreSnapshot neutral = evaluate(board, GameStateEnum.PLAY);
        ScoreSnapshot checked = evaluate(board, GameStateEnum.BLACK_IN_CHECK);

        assertEquals(Score.CHECK, checked.midgame() - neutral.midgame());
        assertEquals(Score.CHECK, checked.endgame() - neutral.endgame());
        assertEquals(Score.CHECK, checked.blended() - neutral.blended());
    }

    @Test
    void beingInCheckSubtractsBonus() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/4q3/4K3 w - - 0 1");

        ScoreSnapshot neutral = evaluate(board, GameStateEnum.PLAY);
        ScoreSnapshot checked = evaluate(board, GameStateEnum.WHITE_IN_CHECK);

        assertEquals(-Score.CHECK, checked.midgame() - neutral.midgame());
        assertEquals(-Score.CHECK, checked.endgame() - neutral.endgame());
        assertEquals(-Score.CHECK, checked.blended() - neutral.blended());
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

    private static ScoreSnapshot evaluate(BitBoard board, GameStateEnum state) {
        MaterialModule material = new MaterialModule();
        PawnStructureModule pawns = new PawnStructureModule();
        PieceSquareModule pieceSquares = new PieceSquareModule();
        ActivityModule activity = new ActivityModule();
        KingSafetyModule kingSafety = new KingSafetyModule();
        material.setPawnChangeListener(pawns);
        EvaluationPipeline pipeline = new EvaluationPipeline(List.of(
                material,
                pawns,
                pieceSquares,
                activity,
                kingSafety
        ));
        pipeline.initialize(EvaluationContext.from(board, state));
        return new ScoreSnapshot(
                pipeline.getMidgameScore(),
                pipeline.getEndgameScore(),
                pipeline.getBlendedScore()
        );
    }

    private record ScoreSnapshot(int midgame, int endgame, int blended) {
    }
}
