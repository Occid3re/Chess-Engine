package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameStateEnum;

import java.util.Objects;

/**
 * Immutable snapshot of the current evaluation inputs.  Modules receive a context instance during
 * initialization and whenever the board state changes so they can derive any expensive features
 * lazily.
 */
public final class EvaluationContext {

    private final BitBoard board;
    private final GameStateEnum gameState;
    private final int phase;
    private final long whiteAttackMap;
    private final long blackAttackMap;
    private final boolean whiteToMove;

    private EvaluationContext(BitBoard board, GameStateEnum gameState, int phase,
                              long whiteAttackMap, long blackAttackMap, boolean whiteToMove) {
        this.board = Objects.requireNonNull(board, "board");
        this.gameState = gameState;
        this.phase = phase;
        this.whiteAttackMap = whiteAttackMap;
        this.blackAttackMap = blackAttackMap;
        this.whiteToMove = whiteToMove;
    }

    public static EvaluationContext from(BitBoard bitBoard, GameStateEnum gameState) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        BitBoard snapshot = bitBoard.snapshotWithoutHistory();
        long whiteAttacks = bitBoard.getAttackBitboard(true);
        long blackAttacks = bitBoard.getAttackBitboard(false);
        return new EvaluationContext(snapshot, gameState, bitBoard.getPhase(), whiteAttacks, blackAttacks,
                bitBoard.isWhitesTurn());
    }

    public BitBoard getBoard() {
        return board;
    }

    public GameStateEnum getGameState() {
        return gameState;
    }

    public int getPhase() {
        return phase;
    }

    public long getWhiteAttackMap() {
        return whiteAttackMap;
    }

    public long getBlackAttackMap() {
        return blackAttackMap;
    }

    public boolean isWhiteToMove() {
        return whiteToMove;
    }

    public EvaluationContext copy() {
        return new EvaluationContext(board.snapshotWithoutHistory(), gameState, phase, whiteAttackMap, blackAttackMap, whiteToMove);
    }
}
