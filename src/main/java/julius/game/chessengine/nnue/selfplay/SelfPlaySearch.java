package julius.game.chessengine.nnue.selfplay;

import julius.game.chessengine.ai.nnue.NnueConfig;
import julius.game.chessengine.ai.nnue.NnueIntegration;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;

import java.util.Random;

/**
 * Simplified synchronous search used for self-play data generation. It selects
 * a pseudo-random move among the legal moves and evaluates positions using the
 * current evaluation function. This placeholder keeps the API surface while
 * avoiding the complexity of the main engine search.
 */
public final class SelfPlaySearch {

    private static final Random RNG = new Random();

    private SelfPlaySearch() {}

    public static int bestMove(Engine engine, int timeMs) {
        MoveList moves = engine.getAllLegalMoves();
        if (moves.size() == 0) {
            return -1;
        }
        int idx = RNG.nextInt(moves.size());
        return moves.getMove(idx);
    }

    public static int evaluateCp(Engine engine, int timeMs) {
        double eval = NnueConfig.ENABLE_NNUE
                ? NnueIntegration.blendedEval(engine.getGameState(), engine.getGameState().isWhitesTurn())
                : (engine.getGameState().getScore().getScoreDifference());
        return (int) Math.max(-2000, Math.min(2000, Math.round(eval * 100.0))); // cp
    }
}
