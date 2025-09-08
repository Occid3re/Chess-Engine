package julius.game.chessengine.helper;

/**
 * Precomputed pawn move tables for quick lookup of pawn pushes and attacks.
 * PAWN_ATTACKS[color][square] gives bitboard of attack targets for a pawn of the
 * given color on the given square. PAWN_PUSHES[color][square] gives the square
 * one step forward. These tables are initialised once at class load time.
 */
public class PawnMoveTables {

    public static final long[][] PAWN_ATTACKS = new long[2][64];
    public static final long[][] PAWN_PUSHES = new long[2][64];

    static {
        for (int square = 0; square < 64; square++) {
            int rank = square / 8;
            int file = square % 8;

            long whiteAttacks = 0L;
            long blackAttacks = 0L;
            long whitePush = 0L;
            long blackPush = 0L;

            // White moves upwards (towards higher ranks)
            if (rank < 7) {
                whitePush = 1L << ((rank + 1) * 8 + file);
                if (file > 0) {
                    whiteAttacks |= 1L << ((rank + 1) * 8 + (file - 1));
                }
                if (file < 7) {
                    whiteAttacks |= 1L << ((rank + 1) * 8 + (file + 1));
                }
            }

            // Black moves downwards (towards lower ranks)
            if (rank > 0) {
                blackPush = 1L << ((rank - 1) * 8 + file);
                if (file > 0) {
                    blackAttacks |= 1L << ((rank - 1) * 8 + (file - 1));
                }
                if (file < 7) {
                    blackAttacks |= 1L << ((rank - 1) * 8 + (file + 1));
                }
            }

            PAWN_PUSHES[0][square] = whitePush;
            PAWN_PUSHES[1][square] = blackPush;
            PAWN_ATTACKS[0][square] = whiteAttacks;
            PAWN_ATTACKS[1][square] = blackAttacks;
        }
    }

    private PawnMoveTables() {
        // utility class
    }
}
