package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.figures.PieceType;

import java.util.Objects;

/**
 * Immutable snapshot of the current evaluation inputs.  Modules receive a context instance during
 * initialization and whenever the board state changes so they can derive any expensive features
 * lazily.
 */
public final class EvaluationContext {

    private final ImmutableBoardView boardView;
    private GameStateEnum gameState;
    private int phase;
    private long whiteAttackMap;
    private long blackAttackMap;

    private EvaluationContext(ImmutableBoardView boardView, GameStateEnum gameState, int phase,
                              long whiteAttackMap, long blackAttackMap) {
        this.boardView = Objects.requireNonNull(boardView, "boardView");
        this.gameState = gameState;
        this.phase = phase;
        this.whiteAttackMap = whiteAttackMap;
        this.blackAttackMap = blackAttackMap;
    }

    public static EvaluationContext from(BitBoard bitBoard, GameStateEnum gameState) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        ImmutableBoardView view = ImmutableBoardView.from(bitBoard);
        return new EvaluationContext(view, gameState, bitBoard.getPhase(),
                view.getWhiteAttackMap(), view.getBlackAttackMap());
    }

    public void updateFrom(BitBoard bitBoard, GameStateEnum gameState) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        boardView.copyFrom(bitBoard);
        this.gameState = gameState;
        this.phase = bitBoard.getPhase();
        this.whiteAttackMap = boardView.getWhiteAttackMap();
        this.blackAttackMap = boardView.getBlackAttackMap();
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
        return boardView.isWhitesTurn();
    }

    public long getWhitePawns() {
        return boardView.getWhitePawns();
    }

    public long getBlackPawns() {
        return boardView.getBlackPawns();
    }

    public long getWhiteKnights() {
        return boardView.getWhiteKnights();
    }

    public long getBlackKnights() {
        return boardView.getBlackKnights();
    }

    public long getWhiteBishops() {
        return boardView.getWhiteBishops();
    }

    public long getBlackBishops() {
        return boardView.getBlackBishops();
    }

    public long getWhiteRooks() {
        return boardView.getWhiteRooks();
    }

    public long getBlackRooks() {
        return boardView.getBlackRooks();
    }

    public long getWhiteQueens() {
        return boardView.getWhiteQueens();
    }

    public long getBlackQueens() {
        return boardView.getBlackQueens();
    }

    public long getWhiteKing() {
        return boardView.getWhiteKing();
    }

    public long getBlackKing() {
        return boardView.getBlackKing();
    }

    public long getWhitePieces() {
        return boardView.getWhitePieces();
    }

    public long getBlackPieces() {
        return boardView.getBlackPieces();
    }

    public long getAllPieces() {
        return boardView.getAllPieces();
    }

    public boolean isWhiteKingHasCastled() {
        return boardView.isWhiteKingHasCastled();
    }

    public boolean isBlackKingHasCastled() {
        return boardView.isBlackKingHasCastled();
    }

    public boolean isWhiteKingMoved() {
        return boardView.isWhiteKingMoved();
    }

    public boolean isBlackKingMoved() {
        return boardView.isBlackKingMoved();
    }

    public boolean isWhiteRookA1Moved() {
        return boardView.isWhiteRookA1Moved();
    }

    public boolean isWhiteRookH1Moved() {
        return boardView.isWhiteRookH1Moved();
    }

    public boolean isBlackRookA8Moved() {
        return boardView.isBlackRookA8Moved();
    }

    public boolean isBlackRookH8Moved() {
        return boardView.isBlackRookH8Moved();
    }

    public int getHalfmoveClock() {
        return boardView.getHalfmoveClock();
    }

    public int getFullmoveNumber() {
        return boardView.getFullmoveNumber();
    }

    public int getLastMoveDoubleStepPawnIndex() {
        return boardView.getLastMoveDoubleStepPawnIndex();
    }

    public PieceType getPieceTypeAtIndex(int index) {
        return boardView.getPieceTypeAtIndex(index);
    }

    public ImmutableBoardView getBoardView() {
        return boardView;
    }

    public EvaluationContext copy() {
        return new EvaluationContext(boardView.copy(), gameState, phase, whiteAttackMap, blackAttackMap);
    }
}
