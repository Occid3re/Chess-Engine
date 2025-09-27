package julius.game.chessengine.engine;

import julius.game.chessengine.board.MoveList;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class EngineLegalMovesSnapshotTest {

    @Test
    void getAllLegalMovesReturnsSnapshotInstance() {
        Engine engine = new Engine();

        MoveList first = engine.getAllLegalMoves();
        MoveList second = engine.getAllLegalMoves();

        assertSame(first, second, "Expected cached snapshot instance to be reused");
        assertTrue(first.size() > 0, "Initial position should expose legal moves");
    }

    @Test
    void snapshotIsReusedAndUpdatedAfterMove() {
        Engine engine = new Engine();

        MoveList initialSnapshot = engine.getAllLegalMoves();
        assertTrue(initialSnapshot.size() > 0, "Initial snapshot must contain moves");

        int[] initialMoves = initialSnapshot.toArray();
        int move = initialMoves[0];

        engine.performMove(move);

        MoveList afterMoveSnapshot = engine.getAllLegalMoves();

        assertSame(initialSnapshot, afterMoveSnapshot,
                "Snapshot instance should be recycled via the pool");
        assertTrue(afterMoveSnapshot.size() > 0, "Updated snapshot should also contain moves");
        assertFalse(Arrays.equals(initialMoves, afterMoveSnapshot.toArray()),
                "Legal moves should change after performing a move");

        MoveList cachedView = engine.getAllLegalMoves();
        assertSame(afterMoveSnapshot, cachedView,
                "Subsequent calls should return the published snapshot");
    }
}
