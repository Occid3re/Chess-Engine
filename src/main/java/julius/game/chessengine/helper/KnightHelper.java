package julius.game.chessengine.helper;

public class KnightHelper {

    public static final long[] knightMoveTable = new long[64];

    public static final int[] KNIGHT_MIDGAME_POSITIONAL_VALUES = {
            // R1
            -50,-40,-30,-30,-30,-30,-40,-50,
            // R2
            -40,-20,  0,  0,  0,  0,-20,-40,
            // R3
            -30,  0, 10, 15, 15, 10,  0,-30,
            // R4
            -30,  5, 15, 20, 20, 15,  5,-30,
            // R5
            -30,  0, 15, 20, 20, 15,  0,-30,
            // R6
            -30,  5, 10, 15, 15, 10,  5,-30,
            // R7
            -40,-20,  0,  5,  5,  0,-20,-40,
            // R8
            -50,-40,-30,-30,-30,-30,-40,-50
    };

    public static final int[] KNIGHT_ENDGAME_POSITIONAL_VALUES = {
            // R1
            -50,-40,-30,-30,-30,-30,-40,-50,
            // R2
            -40,-20,  0,  0,  0,  0,-20,-40,
            // R3
            -30,  0, 10, 15, 15, 10,  0,-30,
            // R4
            -30,  5, 15, 20, 20, 15,  5,-30,
            // R5
            -30,  0, 15, 20, 20, 15,  0,-30,
            // R6
            -30,  5, 10, 15, 15, 10,  5,-30,
            // R7
            -40,-20,  0,  5,  5,  0,-20,-40,
            // R8
            -50,-40,-30,-30,-30,-30,-40,-50
    };


    static {
        for (int i = 0; i < 64; i++) {
            knightMoveTable[i] = getKnightMoves(i);
        }
    }

    private static long getKnightMoves(int position) {
        long bitboard = 0L;
        int[] offsets = {-17, -15, -10, -6, 6, 10, 15, 17};
        int rank = position / 8, file = position % 8;

        for (int offset : offsets) {
            int target = position + offset;
            int targetRank = target / 8, targetFile = target % 8;

            // Check if the target position is within the board limits
            if (target >= 0 && target < 64 && Math.abs(rank - targetRank) <= 2 && Math.abs(file - targetFile) <= 2) {
                bitboard |= 1L << target;
            }
        }

        return bitboard;
    }
}
