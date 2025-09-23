package julius.game.chessengine.helper;

public class KnightHelper {

    public static final long[] knightMoveTable = new long[64];

    public static final int[] KNIGHT_MIDGAME_POSITIONAL_VALUES = {
            // R1
            -5, -4, -3, -3, -3, -3, -4, -5,
            // R2
            -4, -2, 0, 0, 0, 0, -2, -4,
            // R3
            -3, 0, 10, 15, 15, 10, 0, -3,
            // R4
            -3, 5, 15, 20, 20, 15, 5, -3,
            // R5
            -3, 0, 15, 20, 20, 15, 0, -3,
            // R6
            -3, 5, 10, 15, 15, 10, 5, -3,
            // R7
            -4, -2, 0, 5, 5, 0, -2, -4,
            // R8
            -5, -4, -3, -3, -3, -3, -4, -5
    };

    public static final int[] KNIGHT_ENDGAME_POSITIONAL_VALUES = {
            // R1
            -5, -4, -3, -3, -3, -3, -4, -5,
            // R2
            -4, -2, 0, 0, 0, 0, -2, -4,
            // R3
            -3, 0, 10, 15, 15, 10, 0, -3,
            // R4
            -3, 5, 15, 20, 20, 15, 5, -3,
            // R5
            -3, 0, 15, 20, 20, 15, 0, -3,
            // R6
            -3, 5, 10, 15, 15, 10, 5, -3,
            // R7
            -4, -2, 0, 5, 5, 0, -2, -4,
            // R8
            -5, -4, -3, -3, -3, -3, -4, -5
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
