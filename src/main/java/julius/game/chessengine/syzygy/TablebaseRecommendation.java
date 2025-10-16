package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.Color;
import julius.game.chessengine.figures.PieceType;

import java.util.Optional;

/**
 * Encapsulates a Syzygy recommendation mapped to the engine's move encoding.
 * The {@link #encodedMove()} is cached so subsequent consumers can promote
 * the move without re-running the mapping logic.
 */
public record TablebaseRecommendation(long boardHash, SyzygyMove syzygyMove, int encodedMove) {

    public static Optional<TablebaseRecommendation> resolve(BitBoard board, SyzygyMove suggestion) {
        if (board == null || suggestion == null) {
            return Optional.empty();
        }
        Integer encoded = encodeMove(board, suggestion);
        if (encoded == null) {
            return Optional.empty();
        }
        return Optional.of(new TablebaseRecommendation(board.getBoardStateHash(), suggestion, encoded));
    }

    public boolean matches(long hash) {
        return this.boardHash == hash;
    }

    private static Integer encodeMove(BitBoard board, SyzygyMove suggestion) {
        int from = suggestion.fromIndex();
        int to = suggestion.toIndex();

        PieceType mover = board.getPieceTypeAtIndex(from);
        if (mover == null) {
            return null;
        }

        Color moverColor = board.getPieceColorAtIndex(from);
        if (moverColor == null) {
            return null;
        }
        boolean isWhite = moverColor == Color.WHITE;

        PieceType captured = null;
        boolean isCapture = false;
        boolean isEnPassant = false;
        boolean isCastling = mover == PieceType.KING && Math.abs(from - to) == 2;

        if (!isCastling && board.isOccupied(to)) {
            Color targetColor = board.getPieceColorAtIndex(to);
            if (targetColor == moverColor) {
                return null;
            }
            captured = board.getPieceTypeAtIndex(to);
            isCapture = captured != null;
        } else if (!isCastling && mover == PieceType.PAWN) {
            int epIndex = board.getEnPassantTargetIndex();
            if (epIndex >= 0 && epIndex == to && !board.isOccupied(to)) {
                isCapture = true;
                isEnPassant = true;
                captured = PieceType.PAWN;
            }
        }

        PieceType promotion = null;
        if (suggestion.promotionPieceTypeBits() != 0) {
            promotion = MoveHelper.intToPieceType(suggestion.promotionPieceTypeBits());
        }

        boolean kingFirstMove = (mover == PieceType.KING) && board.hasKingNotMoved(isWhite);
        boolean rookFirstMove = (mover == PieceType.ROOK) && !board.hasRookMoved(from);
        int castlingStateBits = board.getCastlingStateBits();

        return MoveHelper.createMoveInt(from, to, mover, isWhite, isCapture, isCastling, isEnPassant,
                promotion, captured, kingFirstMove, rookFirstMove, castlingStateBits);
    }
}

