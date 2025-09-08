package julius.game.chessengine.helper;

public class KnightHelper {

    public static final long[] knightMoveTable = new long[64];

    // The possible moves for a knight from its current position
    public static final int[][] knightMoves = {
            {-2, -1}, {-2, 1}, // Upwards L-moves
            {2, -1}, {2, 1},   // Downwards L-moves
            {-1, -2}, {1, -2}, // Leftwards L-moves
            {-1, 2}, {1, 2}    // Rightwards L-moves
    };

    public static final int[] KNIGHT_POSITIONAL_VALUES = {
            -30, -20, -10, -10, -10, -10, -20, -30,
            -20,  -6,  -2,   0,   0,  -2,  -6, -20,
            -10,  -2,   5,   7,   7,   5,  -2, -10,
            -5,    0,   7,  11,  11,   7,   0,  -5,
            -5,    0,   7,  11,  11,   7,   0,  -5,
            -10,  -2,   5,   7,   7,   5,  -2, -10,
            -20,  -6,  -2,   0,   0,  -2,  -6, -20,
            -30, -20, -10, -10, -10, -10, -20, -30,
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
