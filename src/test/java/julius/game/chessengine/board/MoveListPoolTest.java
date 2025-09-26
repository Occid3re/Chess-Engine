package julius.game.chessengine.board;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoveListPoolTest {

    @BeforeEach
    void setUp() {
        MoveListPool.reset();
    }

    @AfterEach
    void tearDown() {
        MoveListPool.reset();
    }

    @Test
    void borrowedListsAreCleared() {
        MoveList list = MoveListPool.borrow();
        try {
            assertEquals(0, list.size(), "Newly borrowed list should start empty");
        } finally {
            MoveListPool.release(list);
        }
    }

    @Test
    void releasedListsAreReusedAndCleared() {
        MoveList first = MoveListPool.borrow();
        first.add(123);
        MoveListPool.release(first);

        MoveList second = MoveListPool.borrow();
        try {
            assertSame(first, second, "Pool should reuse released instances");
            assertEquals(0, second.size(), "Reused instance must be cleared");
        } finally {
            MoveListPool.release(second);
        }
    }

    @Test
    void copyIntoProducesIndependentSnapshot() {
        MoveList source = MoveListPool.borrow();
        try {
            source.add(1);
            source.add(2);
            source.add(3);

            MoveList snapshot = new MoveList();
            source.copyInto(snapshot);

            assertEquals(source.size(), snapshot.size());
            for (int i = 0; i < source.size(); i++) {
                assertEquals(source.getMove(i), snapshot.getMove(i));
            }

            snapshot.setMove(0, 99);
            assertNotEquals(source.getMove(0), snapshot.getMove(0),
                    "Snapshots should not alias the source backing array");
        } finally {
            MoveListPool.release(source);
        }
    }
}
