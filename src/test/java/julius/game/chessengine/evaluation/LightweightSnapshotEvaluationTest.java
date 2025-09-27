package julius.game.chessengine.evaluation;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightweightSnapshotEvaluationTest {

    private static final String COMPLEX_FEN = "r3k2r/pppb1ppp/2n1bn2/2Pp4/3P4/2N1PN2/PPPB1PPP/R3K2R w KQkq d6 7 12";

    @Test
    void evaluationSnapshotsRemainLightweightAndAccurate() {
        BitBoard board = FEN.translateFENtoBitBoard(COMPLEX_FEN);
        BitBoard baseline = new BitBoard(board);

        Score score = Score.initializeScore(board);
        Score baselineScore = Score.initializeScore(baseline);

        int baselineMidgame = baselineScore.getMidgameScore();
        int baselineEndgame = baselineScore.getEndgameScore();
        int baselineBlended = baselineScore.getBlendedScore();

        long baselineWhiteAttacks = baseline.getAttackBitboard(true);
        long baselineBlackAttacks = baseline.getAttackBitboard(false);
        int baselinePhase = baseline.getPhase();
        long baselineHash = baseline.getBoardStateHash();

        Deque<Integer> played = new ArrayDeque<>();

        for (int ply = 0; ply < 6; ply++) {
            IntArrayList moves = board.getAllCurrentPossibleMoves();
            assertTrue(moves.size() > 0, "No legal move available at ply " + ply);
            int move = moves.getInt(0);
            played.push(move);
            board.performMove(move);
            score.applyMove(board, move, null);
        }

        score.refresh(board, null);

        while (!played.isEmpty()) {
            int move = played.pop();
            board.undoMove(move);
            score.undoMove(board, move, null);
        }

        score.refresh(board, null);

        long finalWhiteAttacks = board.getAttackBitboard(true);
        long finalBlackAttacks = board.getAttackBitboard(false);

        assertEquals(baseline, board, "Board state differs after undo sequence");
        assertEquals(baselinePhase, board.getPhase(), "Phase mismatch after undo");
        assertEquals(baselineWhiteAttacks, finalWhiteAttacks, "White attack map mismatch");
        assertEquals(baselineBlackAttacks, finalBlackAttacks, "Black attack map mismatch");
        assertEquals(baselineHash, board.getBoardStateHash(), "Zobrist hash mismatch after undo");

        assertEquals(baselineMidgame, score.getMidgameScore(), "Midgame score changed after refresh/undo cycle");
        assertEquals(baselineEndgame, score.getEndgameScore(), "Endgame score changed after refresh/undo cycle");
        assertEquals(baselineBlended, score.getBlendedScore(), "Blended score changed after refresh/undo cycle");
    }
}
