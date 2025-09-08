package julius.game.chessengine.helper;

import lombok.Getter;

import java.util.Random;

public class ZobristTable {

    private static final int PIECE_TYPES = 12;
    private static final int SQUARES = 64;
    private static final int CASTLING_RIGHTS = 4; // Two for each player (kingside, queenside)
    private static final long[][] pieceSquareTable = new long[PIECE_TYPES][SQUARES];
    private static final long[] castlingRightsHash = new long[CASTLING_RIGHTS];
    private static final long[] enPassantSquareHash = new long[SQUARES];
    // Method to get the hash value indicating it's Black's turn
    @Getter
    private static final long blackTurnHash;

    static {
        long seed = 313L; // Example seed, you can choose any long value
        Random rand = new Random(seed);

        // Initialize piece-square table
        for (int pieceType = 0; pieceType < PIECE_TYPES; pieceType++) {
            for (int square = 0; square < SQUARES; square++) {
                pieceSquareTable[pieceType][square] = rand.nextLong();
            }
        }

        // Initialize castling rights hash values
        for (int i = 0; i < CASTLING_RIGHTS; i++) {
            castlingRightsHash[i] = rand.nextLong();
        }

        // Initialize en passant square hash values
        for (int i = 0; i < SQUARES; i++) {
            enPassantSquareHash[i] = rand.nextLong();
        }

        // Initialize hash value for black's turn
        blackTurnHash = rand.nextLong();
    }

    public static long getPieceSquareHash(int pieceType, int square) {
        return pieceSquareTable[pieceType][square];
    }

    public static long getCastlingRightsHash(int castlingRightIndex) {
        return castlingRightsHash[castlingRightIndex];
    }

    // Method to get the hash value for a specific en passant file
    public static long getEnPassantSquareHash(int squareIndex) {
        return enPassantSquareHash[squareIndex];
    }

}
