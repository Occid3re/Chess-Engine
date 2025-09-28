package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;

/**
 * Functional interface used to create {@link Score} instances for a given board state. The
 * implementation is responsible for returning a fully initialised {@link Score} ready for use by
 * the engine.
 */
@FunctionalInterface
public interface ScoreFactory {
    Score create(BitBoard bitBoard);
}
