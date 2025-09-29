package julius.game.chessengine.board;

import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.utils.Color;

import static julius.game.chessengine.board.MoveHelper.*;
import static org.junit.jupiter.api.Assertions.*;

public final class BoardStateAssertions {

    private BoardStateAssertions() {
    }

    public static void assertBoardConsistent(BitBoard board, String context) {
        long whiteUnion = board.getWhitePawns()
                | board.getWhiteKnights()
                | board.getWhiteBishops()
                | board.getWhiteRooks()
                | board.getWhiteQueens()
                | board.getWhiteKing();

        long blackUnion = board.getBlackPawns()
                | board.getBlackKnights()
                | board.getBlackBishops()
                | board.getBlackRooks()
                | board.getBlackQueens()
                | board.getBlackKing();

        assertEquals(whiteUnion, board.getWhitePieces(), context + " - white aggregate mismatch");
        assertEquals(blackUnion, board.getBlackPieces(), context + " - black aggregate mismatch");
        assertEquals(0L, whiteUnion & blackUnion, context + " - overlapping white/black occupancy");
        assertEquals(whiteUnion | blackUnion, board.getAllPieces(),
                context + " - combined occupancy mismatch");

        for (int square = 0; square < 64; square++) {
            long mask = 1L << square;
            PieceType piece = board.getPieceTypeAtIndex(square);
            boolean whiteBit = (whiteUnion & mask) != 0;
            boolean blackBit = (blackUnion & mask) != 0;
            boolean aggregatedOccupancy = whiteBit || blackBit;
            boolean boardOccupancy = board.isOccupied(square);

            String squareLabel = context + " @" + convertIndexToString(square);

            if (piece == null) {
                assertFalse(aggregatedOccupancy, squareLabel + " - aggregate occupied but no piece recorded");
                assertFalse(boardOccupancy, squareLabel + " - occupancy flag set without piece");
                assertNull(board.getPieceColorAtIndex(square), squareLabel + " - color reported without piece");
            } else {
                assertTrue(aggregatedOccupancy, squareLabel + " - missing aggregate bit for piece " + piece);
                assertTrue(boardOccupancy, squareLabel + " - occupancy flag missing for piece " + piece);
                assertTrue(whiteBit ^ blackBit,
                        squareLabel + " - piece " + piece + " associated with both colors");
                Color color = whiteBit ? Color.WHITE : Color.BLACK;
                assertEquals(color, board.getPieceColorAtIndex(square),
                        squareLabel + " - piece color mismatch for " + piece);
                long specific = getPieceBitboard(board, piece, color);
                assertTrue((specific & mask) != 0,
                        squareLabel + " - piece " + piece + " missing from specific bitboard");
            }
        }
    }

    public static String describeMove(int move) {
        PieceType mover = intToPieceType(derivePieceTypeBits(move));
        String notation = mover + " " + convertIndexToString(deriveFromIndex(move))
                + "-" + convertIndexToString(deriveToIndex(move));
        if (isCapture(move)) {
            notation += "x";
        }
        int promotionBits = derivePromotionPieceTypeBits(move);
        if (promotionBits != 0) {
            notation += "=" + intToPieceType(promotionBits);
        }
        return notation;
    }

    private static long getPieceBitboard(BitBoard board, PieceType piece, Color color) {
        return switch (piece) {
            case PAWN -> color == Color.WHITE ? board.getWhitePawns() : board.getBlackPawns();
            case KNIGHT -> color == Color.WHITE ? board.getWhiteKnights() : board.getBlackKnights();
            case BISHOP -> color == Color.WHITE ? board.getWhiteBishops() : board.getBlackBishops();
            case ROOK -> color == Color.WHITE ? board.getWhiteRooks() : board.getBlackRooks();
            case QUEEN -> color == Color.WHITE ? board.getWhiteQueens() : board.getBlackQueens();
            case KING -> color == Color.WHITE ? board.getWhiteKing() : board.getBlackKing();
        };
    }
}
