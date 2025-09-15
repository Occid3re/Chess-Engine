package julius.game.chessengine.helper;

import lombok.extern.log4j.Log4j2;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.BitHelper.bitIndex;
import static julius.game.chessengine.helper.BitHelper.fileBitboard;

@Log4j2
public class PawnHelper {

    public final static int[] WHITE_PAWN_POSITIONAL_VALUES = {
            // R1
            0,  0,  0,  0,  0,  0,  0,  0,
            // R2
            5, 10, 10,-20,-20,10, 10,  5,
            // R3
            5, -5,-10,  0,  0,-10, -5,  5,
            // R4
            0,  0,  0, 20, 20,  0,  0,  0,
            // R5
            5,  5, 10, 25, 25, 10,  5,  5,
            // R6
            10, 10, 20, 30, 30, 20, 10, 10,
            // R7
            50, 50, 50, 50, 50, 50, 50, 50,
            // R8
            0,  0,  0,  0,  0,  0,  0,  0
    };

    public final static int[] BLACK_PAWN_POSITIONAL_VALUES = {
            // mirror of white (rank-flipped)
            0,  0,  0,  0,  0,  0,  0,  0, // R1
            50, 50, 50, 50, 50, 50, 50, 50, // R2
            10, 10, 20, 30, 30, 20, 10, 10, // R3
            5,  5, 10, 25, 25, 10,  5,  5, // R4
            0,  0,  0, 20, 20,  0,  0,  0, // R5
            5, -5,-10,  0,  0,-10, -5,  5, // R6
            5, 10, 10,-20,-20,10, 10,  5, // R7
            0,  0,  0,  0,  0,  0,  0,  0  // R8
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
    // Method to count pawn islands (groups of pawns on adjacent files)
    public static int countPawnIslands(long pawnsBitboard) {
        int islands = 0;
        boolean previousFileHasPawn = false;
        for (char file = 'a'; file <= 'h'; file++) {
            long fileMask = fileBitboard(file);
            boolean hasPawn = (pawnsBitboard & fileMask) != 0;
            if (hasPawn && !previousFileHasPawn) {
                islands++;
            }
            previousFileHasPawn = hasPawn;
        }
        return islands;
    }

    // Method to count connected pawns (side-by-side pawns on the same rank)
    public static int countConnectedPawns(long pawnsBitboard) {
        long eastConnections = (pawnsBitboard & ~FileMasks[7]) << 1 & pawnsBitboard;
        long westConnections = (pawnsBitboard & ~FileMasks[0]) >>> 1 & pawnsBitboard;
        long connected = eastConnections | westConnections;
        return Long.bitCount(connected);
    }
}
