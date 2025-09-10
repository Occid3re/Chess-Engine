package julius.game.chessengine.ai.nnue;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameState;

/**
 * Blends NNUE evaluation with the classical handcrafted score.
 */
public final class NnueIntegration {
    private NnueIntegration() {}

    public static double blendedEval(GameState state, BitBoard board, boolean isWhitesTurn) {
        double nn = NnueEvaluator.INSTANCE.evalCp(board) / 100.0;
        double classical = state.getScore().getScoreDifference();
        double blend = NnueConfig.BLEND * nn + (1.0 - NnueConfig.BLEND) * classical;
        return isWhitesTurn ? blend : -blend;

    }
}
