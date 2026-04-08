package julius.game.chessengine.evaluation;

/**
 * Piece-Square Tables for midgame and endgame evaluation.
 * Values are from White's perspective with a1=0, h8=63 mapping.
 * Black values are obtained by mirroring: {@code table[square ^ 56]}.
 *
 * Based on PeSTO evaluation tables, scaled to 50% to avoid double-counting
 * with ActivityModule (mobility/center control), KingSafetyModule (king placement),
 * and PawnStructureModule (pawn structure). King tables are additionally
 * zero-centered since KingSafetyModule already evaluates king safety.
 *
 * Index 0 = unused, 1 = PAWN, 2 = KNIGHT, 3 = BISHOP, 4 = ROOK, 5 = QUEEN, 6 = KING
 */
public final class PieceSquareTables {

    private PieceSquareTables() {}

    // ────────────── MIDGAME ──────────────

    private static final int[] MG_PAWN = {
          0,    0,    0,    0,    0,    0,    0,    0,
          0,   -3,   -5,  -17,   -6,    3,    2,   -2,
          1,    1,   -3,   -9,   -3,   -3,   -2,    5,
          1,    7,    4,    2,    3,    2,    5,    4,
          5,   15,   12,   16,   16,   12,    9,    6,
         11,   27,   36,   28,   39,   48,   36,    6,
         59,   61,   87,   84,   54,   41,   56,   69,
          0,    0,    0,    0,    0,    0,    0,    0
    };

    private static final int[] MG_KNIGHT = {
        -87,  -46,  -37,  -36,  -36,  -37,  -46,  -87,
        -38,  -20,  -13,   -7,   -7,  -13,  -20,  -38,
        -30,   -8,    3,    6,    6,    3,   -8,  -30,
        -17,    4,   20,   25,   25,   20,    4,  -17,
        -17,    7,   22,   26,   26,   22,    7,  -17,
         -4,   11,   29,   27,   27,   29,   11,   -4,
        -33,  -13,    2,   19,   19,    2,  -13,  -33,
       -100,  -41,  -28,  -13,  -13,  -28,  -41, -100
    };

    private static final int[] MG_BISHOP = {
        -26,   -2,   -4,  -11,  -11,   -4,   -2,  -26,
         -7,    4,   10,    2,    2,   10,    4,   -7,
         -3,   11,   -2,    9,    9,   -2,   11,   -3,
         -2,    6,   13,   20,   20,   13,    6,   -2,
         -6,   15,   11,   16,   16,   11,   15,   -6,
         -8,    3,    1,    6,    6,    1,    3,   -8,
         -8,   -7,    3,    0,    0,    3,   -7,   -8,
        -24,    1,   -7,  -11,  -11,   -7,    1,  -24
    };

    private static final int[] MG_ROOK = {
        -15,  -10,   -7,   -2,   -2,   -7,  -10,  -15,
        -10,   -6,   -4,    3,    3,   -4,   -6,  -10,
        -12,   -5,    0,    2,    2,    0,   -5,  -12,
         -6,   -2,   -2,   -3,   -3,   -2,   -2,   -6,
          0,    0,    5,    8,    8,    5,    0,    0,
          6,    7,    7,    6,    6,    7,    7,    6,
         10,    6,   13,   13,   13,   13,    6,   10,
          6,    7,    8,   11,   11,    8,    7,    6
    };

    private static final int[] MG_QUEEN = {
          2,   -2,   -2,    2,    2,   -2,   -2,    2,
         -1,    3,    4,    6,    6,    4,    3,   -1,
         -1,    3,    7,    4,    4,    7,    3,   -1,
          2,    3,    5,    4,    4,    5,    3,    2,
          0,    7,    6,    3,    3,    6,    7,    0,
         -2,    5,    3,    4,    4,    3,    5,   -2,
         -2,    3,    5,    4,    4,    5,    3,   -2,
         -1,   -1,    1,   -1,   -1,    1,   -1,   -1
    };

    private static final int[] MG_KING = {
         61,   89,   61,   24,   24,   61,   89,   61,
         64,   77,   42,   15,   15,   42,   77,   64,
         23,   54,   10,  -15,  -15,   10,   54,   23,
          7,   20,   -6,  -26,  -26,   -6,   20,    7,
          2,   15,  -22,  -40,  -40,  -22,   15,    2,
        -13,   -2,  -34,  -59,  -59,  -34,   -2,  -13,
        -31,  -15,  -42,  -58,  -58,  -42,  -15,  -31,
        -45,  -30,  -52,  -75,  -75,  -52,  -30,  -45
    };

    // ────────────── ENDGAME ──────────────

    private static final int[] EG_PAWN = {
          0,    0,    0,    0,    0,    0,    0,    0,
         -8,   -8,   -8,   -8,   -8,   -8,   -8,   -8,
         -5,   -5,   -5,   -5,   -5,   -5,   -5,   -5,
          4,    4,    4,    4,    4,    4,    4,    4,
          8,    8,    8,    8,    8,    8,    8,    8,
         16,   16,   16,   16,   16,   16,   16,   16,
         33,   33,   33,   33,   33,   33,   33,   33,
          0,    0,    0,    0,    0,    0,    0,    0
    };

    private static final int[] EG_KNIGHT = {
        -48,  -32,  -24,  -10,  -10,  -24,  -32,  -48,
        -33,  -27,   -9,    4,    4,   -9,  -27,  -33,
        -20,  -13,   -4,   15,   15,   -4,  -13,  -20,
        -17,   -1,    7,   14,   14,    7,   -1,  -17,
        -22,   -8,    5,   20,   20,    5,   -8,  -22,
        -25,  -22,   -8,    9,    9,   -8,  -22,  -25,
        -34,  -25,  -25,    6,    6,  -25,  -25,  -34,
        -50,  -44,  -28,   -8,   -8,  -28,  -44,  -50
    };

    private static final int[] EG_BISHOP = {
        -28,  -15,  -18,   -6,   -6,  -18,  -15,  -28,
        -18,   -6,   -8,    1,    1,   -8,   -6,  -18,
         -8,    0,   -1,    5,    5,   -1,    0,   -8,
        -10,   -3,    0,    9,    9,    0,   -3,  -10,
         -8,    0,   -7,    8,    8,   -7,    0,   -8,
        -15,    3,    2,    3,    3,    2,    3,  -15,
        -15,  -10,    0,    1,    1,    0,  -10,  -15,
        -23,  -21,  -18,  -12,  -12,  -18,  -21,  -23
    };

    private static final int[] EG_ROOK = {
         -4,   -6,   -5,   -4,   -4,   -5,   -6,   -4,
         -6,   -4,    0,   -1,   -1,    0,   -4,   -6,
          3,   -4,   -1,   -3,   -3,   -1,   -4,    3,
         -3,    1,   -4,    4,    4,   -4,    1,   -3,
         -2,    4,    4,   -3,   -3,    4,    4,   -2,
          3,    1,   -3,    5,    5,   -3,    1,    3,
         -3,    4,   -2,   -2,   -2,   -2,    4,   -3,
          9,    0,   10,    7,    7,   10,    0,    9
    };

    private static final int[] EG_QUEEN = {
        -34,  -28,  -23,  -13,  -13,  -23,  -28,  -34,
        -27,  -15,  -11,   -2,   -2,  -11,  -15,  -27,
        -19,   -9,   -4,    2,    2,   -4,   -9,  -19,
        -11,   -1,    7,   12,   12,    7,   -1,  -11,
        -14,   -3,    5,   11,   11,    5,   -3,  -14,
        -19,   -9,   -6,    1,    1,   -6,   -9,  -19,
        -25,  -13,  -12,   -4,   -4,  -12,  -13,  -25,
        -37,  -26,  -21,  -18,  -18,  -21,  -26,  -37
    };

    private static final int[] EG_KING = {
        -80,  -50,  -22,  -28,  -28,  -22,  -50,  -80,
        -44,  -11,   12,   14,   14,   12,  -11,  -44,
        -19,   10,   39,   44,   44,   39,   10,  -19,
        -10,   30,   42,   42,   42,   42,   30,  -10,
        -14,   38,   62,   62,   62,   62,   38,  -14,
        -17,   42,   51,   56,   56,   51,   42,  -17,
        -48,    3,    0,   10,   10,    0,    3,  -48,
        -73,  -40,  -30,  -26,  -26,  -30,  -40,  -73
    };

    // Index by piece type int (0=unused, 1=PAWN..6=KING)
    private static final int[][] MIDGAME = {
        null, MG_PAWN, MG_KNIGHT, MG_BISHOP, MG_ROOK, MG_QUEEN, MG_KING
    };
    private static final int[][] ENDGAME = {
        null, EG_PAWN, EG_KNIGHT, EG_BISHOP, EG_ROOK, EG_QUEEN, EG_KING
    };

    /**
     * Get midgame PST value for a white piece on the given square.
     * For black, mirror the square: {@code square ^ 56}.
     */
    public static int midgame(int pieceType, int square) {
        return MIDGAME[pieceType][square];
    }

    /**
     * Get endgame PST value for a white piece on the given square.
     * For black, mirror the square: {@code square ^ 56}.
     */
    public static int endgame(int pieceType, int square) {
        return ENDGAME[pieceType][square];
    }
}
