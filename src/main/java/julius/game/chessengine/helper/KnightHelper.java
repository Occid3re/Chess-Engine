package julius.game.chessengine.helper;

public class KnightHelper {

    public static final long[] knightMoveTable = new long[64];

    public static final int[] KNIGHT_MIDGAME_POSITIONAL_VALUES = {
            // R1
            -50, -40, -30, -25, -25, -30, -40, -50,
            // R2
            -35, -20, 0, 5, 5, 0, -20, -35,
            // R3
            -25, 0, 15, 20, 20, 15, 0, -25,
            // R4
            -20, 0, 20, 30, 30, 20, 0, -20,
            // R5
            -20, 5, 20, 30, 30, 20, 5, -20,
            // R6
            -25, 0, 15, 20, 20, 15, 0, -25,
            // R7
            -35, -20, 0, 5, 5, 0, -20, -35,
            // R8
            -50, -40, -30, -25, -25, -30, -40, -50
    };

    public static final int[] KNIGHT_ENDGAME_POSITIONAL_VALUES = {
            // R1
            -40, -30, -20, -15, -15, -20, -30, -40,
            // R2
            -25, -10, 5, 10, 10, 5, -10, -25,
            // R3
            -15, 5, 20, 25, 25, 20, 5, -15,
            // R4
            -10, 5, 25, 35, 35, 25, 5, -10,
            // R5
            -10, 10, 25, 35, 35, 25, 10, -10,
            // R6
            -15, 5, 20, 25, 25, 20, 5, -15,
            // R7
            -25, -10, 5, 10, 10, 5, -10, -25,
            // R8
            -40, -30, -20, -15, -15, -20, -30, -40
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
