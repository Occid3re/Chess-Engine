package julius.game.chessengine.board;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.utils.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BitBoardIntegrityTest {

    private static final String KING_CAPTURE_REGRESSION = "8/8/3q4/4pk2/5pP1/8/4K3/8 b - - 0 44";
    private static final String QUEEN_CAPTURE_REGRESSION = "8/8/4q1k1/4p1p1/5p2/7P/4K3/8 b - - 2 43";

    @Test
    @DisplayName("King capture regression position maintains synchronized state")
    void kingCaptureRegressionPositionMaintainsIntegrity() {
        BitBoard board = FEN.translateFENtoBitBoard(KING_CAPTURE_REGRESSION);
        assertBoardIntegrity(board);

        int move = findMove(board, "f5", "f6", null);

        board.performMove(move);
        assertBoardIntegrity(board);

        board.undoMove(move);
        assertBoardIntegrity(board);
    }

    @Test
    @DisplayName("Queen capture regression position maintains synchronized state")
    void queenCaptureRegressionPositionMaintainsIntegrity() {
        BitBoard board = FEN.translateFENtoBitBoard(QUEEN_CAPTURE_REGRESSION);
        assertBoardIntegrity(board);

        int move = findMove(board, "e6", "f6", null);

        board.performMove(move);
        assertBoardIntegrity(board);

        board.undoMove(move);
        assertBoardIntegrity(board);
    }

    @Test
    @DisplayName("Random play and undo preserves internal board synchronisation")
    void randomizedGamePlayMaintainsIntegrity() {
        BitBoard board = new BitBoard();
        BitBoard baseline = new BitBoard(board);

        Random random = new Random(0x9E3779B97F4A7C15L);
        Deque<Integer> history = new ArrayDeque<>();

        for (int ply = 0; ply < 256; ply++) {
            IntArrayList moves = board.generateAllPossibleMoves(board.isWhitesTurn());
            if (moves.isEmpty()) {
                break;
            }
            int move = moves.getInt(random.nextInt(moves.size()));
            history.push(move);
            board.performMove(move);
            assertBoardIntegrity(board);
        }

        while (!history.isEmpty()) {
            int move = history.pop();
            board.undoMove(move);
            assertBoardIntegrity(board);
        }

        assertBoardsEqual(baseline, board);
    }

    @RepeatedTest(3)
    @DisplayName("Engine move application keeps board and arrays in sync")
    void engineMaintainsIntegrityOnRegressionPositions() {
        Engine engine = new Engine();
        engine.importBoardFromFen(KING_CAPTURE_REGRESSION);
        assertBoardIntegrity(engine.getBitBoard());

        int kingMove = findMove(engine.getBitBoard(), "f5", "f6", null);
        engine.performMove(kingMove);
        assertBoardIntegrity(engine.getBitBoard());
        engine.undoLastMove();
        assertBoardIntegrity(engine.getBitBoard());

        engine.importBoardFromFen(QUEEN_CAPTURE_REGRESSION);
        assertBoardIntegrity(engine.getBitBoard());

        int queenMove = findMove(engine.getBitBoard(), "e6", "f6", null);
        engine.performMove(queenMove);
        assertBoardIntegrity(engine.getBitBoard());
        engine.undoLastMove();
        assertBoardIntegrity(engine.getBitBoard());
    }

    private static int findMove(BitBoard board, String from, String to, PieceType promotion) {
        IntArrayList moves = board.generateAllPossibleMoves(board.isWhitesTurn());
        int fromIndex = MoveHelper.convertStringToIndex(from);
        int toIndex = MoveHelper.convertStringToIndex(to);
        int promotionBits = promotion == null ? 0 : MoveHelper.pieceTypeToInt(promotion);

        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (MoveHelper.deriveFromIndex(move) == fromIndex && MoveHelper.deriveToIndex(move) == toIndex) {
                if (MoveHelper.derivePromotionPieceTypeBits(move) == promotionBits) {
                    return move;
                }
            }
        }
        fail("No move " + from + "->" + to + " found for side " + (board.isWhitesTurn() ? "white" : "black"));
        return -1;
    }

    private static void assertBoardsEqual(BitBoard expected, BitBoard actual) {
        assertEquals(expected.getWhitePawns(), actual.getWhitePawns(), "white pawns");
        assertEquals(expected.getBlackPawns(), actual.getBlackPawns(), "black pawns");
        assertEquals(expected.getWhiteKnights(), actual.getWhiteKnights(), "white knights");
        assertEquals(expected.getBlackKnights(), actual.getBlackKnights(), "black knights");
        assertEquals(expected.getWhiteBishops(), actual.getWhiteBishops(), "white bishops");
        assertEquals(expected.getBlackBishops(), actual.getBlackBishops(), "black bishops");
        assertEquals(expected.getWhiteRooks(), actual.getWhiteRooks(), "white rooks");
        assertEquals(expected.getBlackRooks(), actual.getBlackRooks(), "black rooks");
        assertEquals(expected.getWhiteQueens(), actual.getWhiteQueens(), "white queens");
        assertEquals(expected.getBlackQueens(), actual.getBlackQueens(), "black queens");
        assertEquals(expected.getWhiteKing(), actual.getWhiteKing(), "white king");
        assertEquals(expected.getBlackKing(), actual.getBlackKing(), "black king");
        assertEquals(expected.getWhitePieces(), actual.getWhitePieces(), "white occupancy");
        assertEquals(expected.getBlackPieces(), actual.getBlackPieces(), "black occupancy");
        assertEquals(expected.getAllPieces(), actual.getAllPieces(), "combined occupancy");
        assertEquals(expected.getHalfmoveClock(), actual.getHalfmoveClock(), "halfmove clock");
        assertEquals(expected.getFullmoveNumber(), actual.getFullmoveNumber(), "fullmove number");
        assertEquals(expected.isWhitesTurn(), actual.isWhitesTurn(), "side to move");
        assertEquals(expected.getLastMoveDoubleStepPawnIndex(), actual.getLastMoveDoubleStepPawnIndex(), "en passant anchor");
        assertEquals(expected.isWhiteKingMoved(), actual.isWhiteKingMoved(), "white king moved flag");
        assertEquals(expected.isWhiteRookA1Moved(), actual.isWhiteRookA1Moved(), "white rook a1 moved flag");
        assertEquals(expected.isWhiteRookH1Moved(), actual.isWhiteRookH1Moved(), "white rook h1 moved flag");
        assertEquals(expected.isBlackKingMoved(), actual.isBlackKingMoved(), "black king moved flag");
        assertEquals(expected.isBlackRookA8Moved(), actual.isBlackRookA8Moved(), "black rook a8 moved flag");
        assertEquals(expected.isBlackRookH8Moved(), actual.isBlackRookH8Moved(), "black rook h8 moved flag");
        assertEquals(expected.isWhiteKingHasCastled(), actual.isWhiteKingHasCastled(), "white castled flag");
        assertEquals(expected.isBlackKingHasCastled(), actual.isBlackKingHasCastled(), "black castled flag");
    }

    private static void assertBoardIntegrity(BitBoard board) {
        long computedWhitePawns = 0L;
        long computedBlackPawns = 0L;
        long computedWhiteKnights = 0L;
        long computedBlackKnights = 0L;
        long computedWhiteBishops = 0L;
        long computedBlackBishops = 0L;
        long computedWhiteRooks = 0L;
        long computedBlackRooks = 0L;
        long computedWhiteQueens = 0L;
        long computedBlackQueens = 0L;
        long computedWhiteKing = 0L;
        long computedBlackKing = 0L;

        for (int index = 0; index < 64; index++) {
            PieceType type = board.getPieceTypeAtIndex(index);
            Color color = board.getPieceColorAtIndex(index);
            long mask = 1L << index;

            if (type == null) {
                assertNull(color, "Color present without piece at index " + index);
                assertFalse(board.isOccupied(index), "Occupancy bit set for empty square " + index);
                continue;
            }

            assertNotNull(color, "Missing color for piece at index " + index);
            assertTrue(board.isOccupied(index), "Missing occupancy for occupied square " + index);

            switch (color) {
                case WHITE -> {
                    switch (type) {
                        case PAWN -> computedWhitePawns |= mask;
                        case KNIGHT -> computedWhiteKnights |= mask;
                        case BISHOP -> computedWhiteBishops |= mask;
                        case ROOK -> computedWhiteRooks |= mask;
                        case QUEEN -> computedWhiteQueens |= mask;
                        case KING -> computedWhiteKing |= mask;
                    }
                }
                case BLACK -> {
                    switch (type) {
                        case PAWN -> computedBlackPawns |= mask;
                        case KNIGHT -> computedBlackKnights |= mask;
                        case BISHOP -> computedBlackBishops |= mask;
                        case ROOK -> computedBlackRooks |= mask;
                        case QUEEN -> computedBlackQueens |= mask;
                        case KING -> computedBlackKing |= mask;
                    }
                }
            }
        }

        assertEquals(computedWhitePawns, board.getWhitePawns(), "white pawn bitboard mismatch");
        assertEquals(computedBlackPawns, board.getBlackPawns(), "black pawn bitboard mismatch");
        assertEquals(computedWhiteKnights, board.getWhiteKnights(), "white knight bitboard mismatch");
        assertEquals(computedBlackKnights, board.getBlackKnights(), "black knight bitboard mismatch");
        assertEquals(computedWhiteBishops, board.getWhiteBishops(), "white bishop bitboard mismatch");
        assertEquals(computedBlackBishops, board.getBlackBishops(), "black bishop bitboard mismatch");
        assertEquals(computedWhiteRooks, board.getWhiteRooks(), "white rook bitboard mismatch");
        assertEquals(computedBlackRooks, board.getBlackRooks(), "black rook bitboard mismatch");
        assertEquals(computedWhiteQueens, board.getWhiteQueens(), "white queen bitboard mismatch");
        assertEquals(computedBlackQueens, board.getBlackQueens(), "black queen bitboard mismatch");
        assertEquals(computedWhiteKing, board.getWhiteKing(), "white king bitboard mismatch");
        assertEquals(computedBlackKing, board.getBlackKing(), "black king bitboard mismatch");

        long expectedWhite = computedWhitePawns | computedWhiteKnights | computedWhiteBishops |
                computedWhiteRooks | computedWhiteQueens | computedWhiteKing;
        long expectedBlack = computedBlackPawns | computedBlackKnights | computedBlackBishops |
                computedBlackRooks | computedBlackQueens | computedBlackKing;

        assertEquals(expectedWhite, board.getWhitePieces(), "white occupancy mismatch");
        assertEquals(expectedBlack, board.getBlackPieces(), "black occupancy mismatch");
        assertEquals(expectedWhite | expectedBlack, board.getAllPieces(), "combined occupancy mismatch");
    }

}
