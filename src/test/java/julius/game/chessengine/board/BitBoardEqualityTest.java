package julius.game.chessengine.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BitBoardEqualityTest {

    @Test
    void boardsWithDifferentEnPassantAreNotEqual() {
        BitBoard a = new BitBoard();
        BitBoard b = new BitBoard();

        // Same position initially -> equal & same hash
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        // Diverge ONLY by en-passant target availability
        // Pick a plausible target square index; API will compute EP keying internally.
        // Here we simulate "some double-step landing square" ≠ 0 so EP target becomes valid.
        b.setLastMoveDoubleStepPawnIndex(24); // arbitrary file/rank; just non-zero

        // Now they must differ
        assertNotEquals(a, b, "Boards should differ when only en-passant availability differs.");
        assertNotEquals(a.hashCode(), b.hashCode(), "Hash codes must differ when EP state differs.");
    }

    @Test
    void boardsWithSameEnPassantAreEqual() {
        BitBoard a = new BitBoard();
        BitBoard b = new BitBoard();

        // Set the same EP state on both
        a.setLastMoveDoubleStepPawnIndex(24);
        b.setLastMoveDoubleStepPawnIndex(24);

        assertEquals(a, b, "Boards with identical EP state should be equal.");
        assertEquals(a.hashCode(), b.hashCode(), "Hash codes should match for identical EP state.");
    }

    @Test
    void enPassantZeroVsZeroRemainsEqual() {
        BitBoard a = new BitBoard();
        BitBoard b = new BitBoard();

        a.setLastMoveDoubleStepPawnIndex(0);
        b.setLastMoveDoubleStepPawnIndex(0);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
