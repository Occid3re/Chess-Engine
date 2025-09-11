package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;

/**
 * Lightweight wrapper around {@link BitBoard} used exclusively for search.
 * Generates pseudo-legal moves and checks legality only after making a move.
 */
public final class SearchPosition {
    private final BitBoard board;

    // small reusable buffer for capture generation in quiescence search
    private final MoveList capsBuf = new MoveList();

    public SearchPosition(BitBoard start) {
        this.board = new BitBoard(start); // deep copy
    }

    public long hash() {
        return board.getBoardStateHash();
    }

    public boolean whiteToMove() {
        return board.whitesTurn;
    }

    public boolean inCheck(boolean white) {
        return board.isInCheck(white);
    }

    public boolean isEndgame() {
        return board.isEndgame();
    }

    /**
     * Generates pseudo-legal moves for the side to move. The returned list
     * should be used immediately and not cached.
     */
    public MoveList pseudoMoves() {
        return board.generateAllPossibleMoves(board.whitesTurn);
    }

    /**
     * Returns only captures and promotions for quiescence search.
     */
    public MoveList capturesAndPromotions() {
        MoveList all = board.generateAllPossibleMoves(board.whitesTurn);
        capsBuf.clear();
        for (int i = 0; i < all.size(); i++) {
            int m = all.getMove(i);
            if (MoveHelper.isCapture(m) || MoveHelper.isPawnPromotionMove(m)) {
                capsBuf.add(m);
            }
        }
        return capsBuf;
    }

    public void make(int move) {
        board.performMove(move);
    }

    public void undo(int move) {
        board.undoMove(move);
    }

    /**
     * Executes a null move used for null-move pruning.
     *
     * @return previous en-passant square index so it can be restored
     */
    public int doNullMove() {
        int prev = board.getLastMoveDoubleStepPawnIndex();
        if (prev != 0) board.xorEnPassantForSearch(prev);
        board.setLastMoveDoubleStepPawnIndex(0);
        board.whitesTurn = !board.whitesTurn;
        board.xorSideToMoveForSearch();
        return prev;
    }

    public void undoNullMove(int prevEp) {
        board.whitesTurn = !board.whitesTurn;
        board.xorSideToMoveForSearch();
        board.setLastMoveDoubleStepPawnIndex(prevEp);
        if (prevEp != 0) board.xorEnPassantForSearch(prevEp);
    }

    /**
     * Exposes the underlying bitboard for evaluation purposes.
     */
    public BitBoard view() {
        return board;
    }
}

