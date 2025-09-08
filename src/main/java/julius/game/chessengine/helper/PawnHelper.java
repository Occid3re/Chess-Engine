package julius.game.chessengine.helper;

import lombok.extern.log4j.Log4j2;

import static julius.game.chessengine.helper.BitHelper.bitIndex;
import static julius.game.chessengine.helper.BitHelper.fileBitboard;

@Log4j2
public class PawnHelper {

    public final static int[] WHITE_PAWN_POSITIONAL_VALUES = {
            0, 0, 0, 0, 0, 0, 0, 0, //Rank 1
            0, 2, 4, -12, -12, 4, 2, 0, //Rank 2
            0, 2, 4, 4, 4, 4, 2, 0, //Rank 3
            0, 2, 4, 8, 8, 4, 2, 0, //Rank 4
            0, 2, 4, 8, 8, 4, 2, 0, //Rank 5
            4, 8, 10, 16, 16, 10, 8, 4, //Rank 6
            100, 100, 100, 100, 100, 100, 100, 100, //Rank 7
            0, 0, 0, 0, 0, 0, 0, 0 //Rank 8
    };

    public final static int[] BLACK_PAWN_POSITIONAL_VALUES = {
            0, 0, 0, 0, 0, 0, 0, 0, // Rank 1
            100, 100, 100, 100, 100, 100, 100, 100,// Rank 2 -- close to promotion
            4, 8, 10, 16, 16, 10, 8, 4,// Rank 3
            0, 2, 4, 8, 8, 4, 2, 0, // Rank 4
            0, 2, 4, 8, 8, 4, 2, 0,  // Rank 5
            0, 2, 4, 4, 4, 4, 2, 0,// Rank 6
            0, 2, 4, -12, -12, 4, 2, 0, // Rank 7
            0, 0, 0, 0, 0, 0, 0, 0 // Rank 8
    };

    // Method to count pawns in the center (e4, d4, e5, d5 squares)
    public static int countCenterPawns(long pawnsBitboard) {
        // Bit positions for e4, d4, e5, d5
        long centerSquares = (1L << bitIndex('e', 4)) | (1L << bitIndex('d', 4))
                | (1L << bitIndex('e', 5)) | (1L << bitIndex('d', 5));
        return Long.bitCount(pawnsBitboard & centerSquares);
    }


    // Method to count doubled pawns, which are two pawns of the same color on the same file
    public static int countDoubledPawns(long pawnsBitboard) {
        int doubledPawns = 0;
        for (char file = 'a'; file <= 'h'; file++) {
            long fileBitboard = fileBitboard(file);
            if (Long.bitCount(pawnsBitboard & fileBitboard) > 1) {
                doubledPawns++;
            }
        }
        return doubledPawns;
    }

    // Method to count isolated pawns, which are pawns with no friendly pawns on adjacent files
    public static int countIsolatedPawns(long pawnsBitboard) {
        int isolatedPawns = 0;
        for (char file = 'a'; file <= 'h'; file++) {
            long fileBitboard = fileBitboard(file);
            long adjacentFiles = (file > 'a' ? fileBitboard((char) (file - 1)) : 0L)
                    | (file < 'h' ? fileBitboard((char) (file + 1)) : 0L);
            if ((pawnsBitboard & fileBitboard) != 0 && (pawnsBitboard & adjacentFiles) == 0) {
                isolatedPawns++;
            }
        }
        return isolatedPawns;
    }

    // Helper method to get a bitboard representing a file


}
