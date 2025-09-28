package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CastlingEvaluationTest {

    @Test
    void castledKingReceivesBonusInTaperedScores() {
        BitBoard castled = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/5RK1 w - - 0 1");
        BitBoard uncastled = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/5RK1 w K - 0 1");

        ScoreSnapshot castledScore = evaluatePieceSquares(castled);
        ScoreSnapshot uncastledScore = evaluatePieceSquares(uncastled);

        int phase = castled.getPhase();
        int expectedBonus = PieceSquareModule.castlingBonus() * (PieceSquareModule.blendScale() - phase) / PieceSquareModule.blendScale();

        assertEquals(expectedBonus, castledScore.midgame() - uncastledScore.midgame());
        assertEquals(expectedBonus, castledScore.endgame() - uncastledScore.endgame());
        assertEquals(expectedBonus, castledScore.blended() - uncastledScore.blended());
    }

    @Test
    void exposedKingWithMovedRooksIsPenalized() {
        BitBoard safe = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
        BitBoard exposed = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/R3K2R w - - 0 1");

        ScoreSnapshot safeScore = evaluatePieceSquares(safe);
        ScoreSnapshot exposedScore = evaluatePieceSquares(exposed);

        int phase = safe.getPhase();
        int rookPenalty = PieceSquareModule.notCastledRookMovePenalty()
                * (PieceSquareModule.blendScale() - phase) / PieceSquareModule.blendScale();
        int expectedPenalty = 4 * rookPenalty;

        assertEquals(expectedPenalty, exposedScore.midgame() - safeScore.midgame());
        assertEquals(expectedPenalty, exposedScore.endgame() - safeScore.endgame());
        assertEquals(expectedPenalty, exposedScore.blended() - safeScore.blended());
    }

    private static ScoreSnapshot evaluatePieceSquares(BitBoard board) {
        PieceSquareModule module = new PieceSquareModule();
        EvaluationPipeline pipeline = new EvaluationPipeline(List.of(module));
        pipeline.initialize(EvaluationContext.from(board, null));
        return new ScoreSnapshot(
                pipeline.getMidgameScore(),
                pipeline.getEndgameScore(),
                pipeline.getBlendedScore()
        );
    }

    private record ScoreSnapshot(int midgame, int endgame, int blended) {
    }
}

