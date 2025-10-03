package julius.game.chessengine.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class EngineLegalMoveFilteringTest {

    @Test
    void singleCheckUsesResponseMask() {
        String fen = "4r3/8/8/8/8/8/Q7/4K3 w - - 0 1";
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        IntArrayList legal = engine.getAllLegalMoves();
        IntArrayList legacy = legacyMovesFor(fen);

        assertMoveListsEqual(legacy, legal);

        boolean hasNonKingBlock = legal.intStream().anyMatch(move -> {
            if (MoveHelper.derivePieceTypeBits(move) == 6) {
                return false;
            }
            return MoveHelper.deriveToIndex(move) == MoveHelper.convertStringToIndex("e2");
        });
        assertTrue(hasNonKingBlock, "Expected queen block move to be retained");
    }

    @Test
    void doubleCheckAllowsOnlyKingMoves() {
        String fen = "4k3/8/8/8/1b6/5n2/8/4K3 w - - 0 1";
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        IntArrayList legal = engine.getAllLegalMoves();
        IntArrayList legacy = legacyMovesFor(fen);

        assertMoveListsEqual(legacy, legal);
        assertTrue(legal.intStream().allMatch(move -> MoveHelper.derivePieceTypeBits(move) == 6),
                "Double check should yield king moves only");
    }

    @Test
    void enPassantCaptureWhileInCheckIsPreserved() {
        String fen = "4k3/8/8/3pP3/4K3/8/8/8 w - d6 0 1";
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        IntArrayList legal = engine.getAllLegalMoves();
        IntArrayList legacy = legacyMovesFor(fen);

        assertMoveListsEqual(legacy, legal);

        boolean hasEnPassant = legal.intStream().anyMatch(MoveHelper::isEnPassantMove);
        assertTrue(hasEnPassant, "En passant capture should remain legal while in check");
    }

    private static IntArrayList legacyMovesFor(String fen) {
        BitBoard board = FEN.translateFENtoBitBoard(fen);
        IntArrayList pseudo = new IntArrayList();
        board.generateAllPossibleMovesInto(board.whitesTurn, pseudo);
        IntArrayList legal = new IntArrayList();
        int[] elements = pseudo.elements();
        int count = pseudo.size();
        for (int i = 0; i < count; i++) {
            int move = elements[i];
            board.performMove(move);
            if (!board.isInCheck(MoveHelper.isWhitesMove(move))) {
                legal.add(move);
            }
            board.undoMove(move);
        }
        return legal;
    }

    private static void assertMoveListsEqual(IntArrayList expected, IntArrayList actual) {
        int[] expectedArr = expected.toIntArray();
        int[] actualArr = actual.toIntArray();
        Arrays.sort(expectedArr);
        Arrays.sort(actualArr);
        assertArrayEquals(expectedArr, actualArr, "Legal moves diverge from legacy filtering");
    }
}

