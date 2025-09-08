package julius.game.chessengine.board;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static julius.game.chessengine.board.MoveHelper.convertStringToIndex;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PinnedPieceTest {

    @Test
    public void testPinnedRookMovesAlongLine() {
        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/8/8/8/r1R1K3 w - - 0 1");
        MoveList moves = board.generateAllPossibleMoves(true);
        int from = convertStringToIndex("c1");
        Set<Integer> actual = new HashSet<>();
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getMove(i);
            if (MoveHelper.deriveFromIndex(move) == from) {
                actual.add(MoveHelper.deriveToIndex(move));
            }
        }
        Set<Integer> expected = Set.of(
                convertStringToIndex("a1"),
                convertStringToIndex("b1"),
                convertStringToIndex("d1"));
        assertEquals(expected, actual);
    }

    @Test
    public void testPinnedBishopMovesAlongDiagonal() {
        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/3b4/8/1B6/K7 w - - 0 1");
        MoveList moves = board.generateAllPossibleMoves(true);
        int from = convertStringToIndex("b2");
        Set<Integer> actual = new HashSet<>();
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getMove(i);
            if (MoveHelper.deriveFromIndex(move) == from) {
                actual.add(MoveHelper.deriveToIndex(move));
            }
        }
        Set<Integer> expected = Set.of(
                convertStringToIndex("c3"),
                convertStringToIndex("d4"));
        assertEquals(expected, actual);
    }
}
