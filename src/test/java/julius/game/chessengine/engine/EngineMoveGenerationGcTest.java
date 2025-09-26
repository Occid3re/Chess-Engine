package julius.game.chessengine.engine;

import julius.game.chessengine.board.MoveList;
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineMoveGenerationGcTest {

    @Test
    void cachedSnapshotsRemainStableAfterUndo() {
        Engine engine = new Engine();
        MoveList initialMoves = engine.getAllLegalMoves();
        int size = initialMoves.size();
        int[] snapshot = new int[size];
        for (int i = 0; i < size; i++) {
            snapshot[i] = initialMoves.getMove(i);
        }

        if (size == 0) {
            return; // trivial starting position with no moves
        }

        int move = snapshot[0];
        engine.performMove(move);
        engine.undoLastMove();

        MoveList fromCache = engine.getAllLegalMoves();
        assertEquals(size, fromCache.size(), "Cached list should preserve move count");
        for (int i = 0; i < size; i++) {
            assertEquals(snapshot[i], fromCache.getMove(i), "Cached snapshot should match original ordering");
        }
    }

    @Test
    void stressMoveGenerationAvoidsGcChurn() {
        Engine engine = new Engine();
        long before = totalGcCollections();

        for (int i = 0; i < 256; i++) {
            MoveList moves = engine.getAllLegalMoves();
            if (moves.size() == 0) {
                break;
            }
            int move = moves.getMove(i % moves.size());
            engine.performMove(move);
            engine.undoLastMove();
        }

        long after = totalGcCollections();
        assertTrue(after - before <= 1, "Move generation pooling should eliminate GC churn");
    }

    private static long totalGcCollections() {
        long total = 0;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean bean : beans) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }
}
