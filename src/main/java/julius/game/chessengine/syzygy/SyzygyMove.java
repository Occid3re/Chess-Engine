package julius.game.chessengine.syzygy;

/**
 * Normalised representation of a move suggested by the Syzygy tablebases.
 * The indices follow the engine's 0-based square encoding ({@code a1 = 0}).
 */
public record SyzygyMove(int fromIndex, int toIndex, int promotionPieceTypeBits) {

    public SyzygyMove {
        if (fromIndex < 0 || fromIndex >= 64) {
            throw new IllegalArgumentException("fromIndex out of bounds: " + fromIndex);
        }
        if (toIndex < 0 || toIndex >= 64) {
            throw new IllegalArgumentException("toIndex out of bounds: " + toIndex);
        }
        if (promotionPieceTypeBits < 0 || promotionPieceTypeBits > 7) {
            throw new IllegalArgumentException("Invalid promotion piece bits: " + promotionPieceTypeBits);
        }
    }

    public boolean isPromotion() {
        return promotionPieceTypeBits != 0;
    }
}

