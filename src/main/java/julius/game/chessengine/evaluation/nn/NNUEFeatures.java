package julius.game.chessengine.evaluation.nn;

/**
 * HalfKP feature index computation for NNUE evaluation.
 *
 * <p>Feature scheme: for each perspective (white/black), one binary feature is
 * active per non-king piece on the board. The feature index encodes:
 * {@code kingSquare * NUM_PIECE_FEATURES + pieceIndex * 64 + pieceSquare}
 *
 * <p>Piece index mapping (10 piece types: 5 types x 2 colors):
 * <pre>
 *   0 = own pawn        5 = enemy pawn
 *   1 = own knight      6 = enemy knight
 *   2 = own bishop      7 = enemy bishop
 *   3 = own rook        8 = enemy rook
 *   4 = own queen       9 = enemy queen
 * </pre>
 *
 * <p>"Own" and "enemy" are relative to the perspective side. Feature indices
 * are symmetric: what white sees as "own pawn on e4" is different from what
 * black sees. For black's perspective, squares are mirrored vertically
 * (square XOR 56) so the network sees a consistent orientation.
 */
public final class NNUEFeatures {

    private NNUEFeatures() {}

    /** Number of piece types per side (pawn, knight, bishop, rook, queen). Kings excluded. */
    public static final int PIECE_TYPES = 5;
    /** Number of piece-color combinations (own 5 + enemy 5). */
    public static final int PIECE_INDICES = 10;
    /** Features per king square: 10 piece indices * 64 squares. */
    public static final int FEATURES_PER_KING = PIECE_INDICES * 64;  // 640
    /** Total feature space per perspective: 64 king squares * 640. */
    public static final int TOTAL_FEATURES = 64 * FEATURES_PER_KING; // 40960
    /** Size of the first hidden layer (accumulator width). */
    public static final int ACCUMULATOR_SIZE = 128;

    /**
     * Compute the HalfKP feature index for a piece from a given perspective.
     *
     * @param kingSq     king square of the perspective side (0-63, a1=0, h8=63)
     * @param pieceSq    square of the piece (0-63)
     * @param pieceType  piece type bits (1=pawn..5=queen). Kings are excluded.
     * @param pieceWhite true if the piece is white
     * @param perspWhite true if computing from white's perspective
     * @return feature index in [0, TOTAL_FEATURES), or -1 if invalid
     */
    public static int featureIndex(int kingSq, int pieceSq, int pieceType, boolean pieceWhite, boolean perspWhite) {
        if (pieceType < 1 || pieceType > 5) return -1; // skip kings and invalid

        // For black perspective, mirror all squares vertically
        int ks = perspWhite ? kingSq : (kingSq ^ 56);
        int ps = perspWhite ? pieceSq : (pieceSq ^ 56);

        // Piece index: 0-4 = own pieces, 5-9 = enemy pieces
        boolean isOwn = (pieceWhite == perspWhite);
        int pieceIdx = (pieceType - 1) + (isOwn ? 0 : PIECE_TYPES);

        return ks * FEATURES_PER_KING + pieceIdx * 64 + ps;
    }

    /**
     * Maximum number of active features per perspective (at most 15 non-king pieces
     * can be on the board: 8 pawns + 2N + 2B + 2R + 1Q, and opponent's pieces too).
     * In practice ~20-30 features active.
     */
    public static final int MAX_ACTIVE_FEATURES = 30;
}
