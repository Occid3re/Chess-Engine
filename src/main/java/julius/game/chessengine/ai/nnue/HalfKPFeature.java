package julius.game.chessengine.ai.nnue;

import julius.game.chessengine.board.BitBoard;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility that computes the active HalfKP feature indices for a given {@link BitBoard}.
 *
 * <p>The feature layout is fixed as:</p>
 * <pre>
 * index = ((((side * 64) + kingSquare) * 5 + pieceKind) * 64) + square)
 * side \in {0:white,1:black}, pieceKind \in {0:P,1:N,2:B,3:R,4:Q}
 * </pre>
 */
public final class HalfKPFeature {

    public static final int NUM_PIECE_KINDS = 5;
    public static final int NUM_FEATURES = 2 * 64 * NUM_PIECE_KINDS * 64; // 40960

    private HalfKPFeature() {
    }

    /**
     * Computes all active features for the given board.
     *
     * @param b board to extract features from
     * @return list of active feature indices
     */
    public static List<Integer> featuresFor(BitBoard b) {
        // At most 32 pieces on board -> pre-size a bit larger
        ArrayList<Integer> indices = new ArrayList<>(64);

        int whiteKingSq = Long.numberOfTrailingZeros(b.getWhiteKing());
        int blackKingSq = Long.numberOfTrailingZeros(b.getBlackKing());

        // White pieces
        collectFeatures(indices, 0, whiteKingSq, b.getWhitePawns(), 0);
        collectFeatures(indices, 0, whiteKingSq, b.getWhiteKnights(), 1);
        collectFeatures(indices, 0, whiteKingSq, b.getWhiteBishops(), 2);
        collectFeatures(indices, 0, whiteKingSq, b.getWhiteRooks(), 3);
        collectFeatures(indices, 0, whiteKingSq, b.getWhiteQueens(), 4);

        // Black pieces
        collectFeatures(indices, 1, blackKingSq, b.getBlackPawns(), 0);
        collectFeatures(indices, 1, blackKingSq, b.getBlackKnights(), 1);
        collectFeatures(indices, 1, blackKingSq, b.getBlackBishops(), 2);
        collectFeatures(indices, 1, blackKingSq, b.getBlackRooks(), 3);
        collectFeatures(indices, 1, blackKingSq, b.getBlackQueens(), 4);

        return indices;
    }

    private static void collectFeatures(List<Integer> out, int side, int kingSq, long bitboard, int pieceKind) {
        while (bitboard != 0) {
            long lsb = bitboard & -bitboard;
            int sq = Long.numberOfTrailingZeros(lsb);
            int idx = ((((side * 64) + kingSq) * NUM_PIECE_KINDS + pieceKind) * 64) + sq;
            out.add(idx);
            bitboard ^= lsb;
        }
    }
}
