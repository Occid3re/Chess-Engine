package julius.game.chessengine.helper;

public class BitboardHelper {

    private static final long[][] linesBetween = new long[64][64];

    // Static initializer to precompute the lines between all pairs of squares
    static {
        for (int start = 0; start < 64; start++) {
            for (int end = 0; end < 64; end++) {
                linesBetween[start][end] = calculateLineBetween(start, end);
            }
        }
    }

    // Method to calculate the line between two indices
    private static long calculateLineBetween(int startIndex, int endIndex) {
        int startRank = startIndex / 8;
        int startFile = startIndex % 8;
        int endRank = endIndex / 8;
        int endFile = endIndex % 8;

        long line = 0L;

        // Horizontal line
        if (startRank == endRank) {
            for (int file = Math.min(startFile, endFile) + 1; file < Math.max(startFile, endFile); file++) {
                line |= 1L << (startRank * 8 + file);
            }
        }
        // Vertical line
        else if (startFile == endFile) {
            for (int rank = Math.min(startRank, endRank) + 1; rank < Math.max(startRank, endRank); rank++) {
                line |= 1L << (rank * 8 + startFile);
            }
        }
        // Diagonal line
        else if (Math.abs(startRank - endRank) == Math.abs(startFile - endFile)) {
            int rankDirection = startRank < endRank ? 1 : -1;
            int fileDirection = startFile < endFile ? 1 : -1;

            int rank = startRank + rankDirection;
            int file = startFile + fileDirection;

            while (rank != endRank && file != endFile) {
                line |= 1L << (rank * 8 + file);
                rank += rankDirection;
                file += fileDirection;
            }
        }

        return line;
    }

    // Public static method to get the line between two indices
    public static long lineBetweenIndices(int startIndex, int endIndex) {
        return linesBetween[startIndex][endIndex];
    }
}
