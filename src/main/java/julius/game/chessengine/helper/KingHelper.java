package julius.game.chessengine.helper;

public class KingHelper {

    public static final long[] KING_ATTACKS = new long[64];

    static {
        // Precompute the king attacks table
        initializeKingAttacks();
    }

    private static void initializeKingAttacks() {
        int[] kingMoves = {-9, -8, -7, -1, 1, 7, 8, 9};
        for (int positionIndex = 0; positionIndex < 64; positionIndex++) {
            long attacks = 0L;
            int originalFile = positionIndex % 8;
            for (int moveOffset : kingMoves) {
                int targetIndex = positionIndex + moveOffset;
                if (targetIndex >= 0 && targetIndex < 64) {
                    int file = targetIndex % 8;
                    if (Math.abs(file - originalFile) <= 1) {
                        attacks |= 1L << targetIndex;
                    }
                }
            }
            KING_ATTACKS[positionIndex] = attacks;
        }
    }

    public static final int[] BLACK_KING_POSITIONAL_VALUES = {
            // mirror of white king MG
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -20,-30,-30,-40,-40,-30,-30,-20,
            -10,-20,-20,-20,-20,-20,-20,-10,
            20, 20,  0,  0,  0,  0, 20, 20,
            20, 30, 10,  0,  0, 10, 30, 20
    };

    public static final int[] WHITE_KING_POSITIONAL_VALUES = {
            20, 30, 10,  0,  0, 10, 30, 20,
            20, 20,  0,  0,  0,  0, 20, 20,
            -10,-20,-20,-20,-20,-20,-20,-10,
            -20,-30,-30,-40,-40,-30,-30,-20,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30
    };

    public static final int[] KING_ENDGAME_POSITIONAL_VALUES = {
            // classic EG king activity table
            -50,-40,-30,-20,-20,-30,-40,-50,
            -30,-20,-10,  0,  0,-10,-20,-30,
            -30,-10, 20, 30, 30, 20,-10,-30,
            -30,-10, 30, 40, 40, 30,-10,-30,
            -30,-10, 30, 40, 40, 30,-10,-30,
            -30,-10, 20, 30, 30, 20,-10,-30,
            -30,-20,-10,  0,  0,-10,-20,-30,
            -50,-40,-30,-20,-20,-30,-40,-50
    };




}
