package julius.game.chessengine.engine.search.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchConfigTest {

    @Test
    void effectiveMaxDepthClampsToAtLeastOne() {
        SearchConfig config = new SearchConfig();
        assertEquals(64, config.effectiveMaxDepth());
        assertTrue(config.setMaxDepth(-5));
        assertEquals(1, config.getMaxDepth());
        assertEquals(1, config.effectiveMaxDepth());
    }

    @Test
    void hashSizeIsClampedBetweenBounds() {
        SearchConfig config = new SearchConfig();
        assertTrue(config.setHashSizeMb(0));
        assertEquals(SearchConfig.MIN_HASH_SIZE_MB, config.getHashSizeMb());
        assertTrue(config.setHashSizeMb(SearchConfig.MAX_HASH_SIZE_MB + 1024));
        assertEquals(SearchConfig.MAX_HASH_SIZE_MB, config.getHashSizeMb());
    }

    @Test
    void computeHashCapacityRoundsToNearestPowerOfTwoWithinBounds() {
        int capacity = SearchConfig.computeHashCapacity(300L, 8, 4, 64);
        assertEquals(64, capacity);

        int minCapacity = SearchConfig.computeHashCapacity(8L, 64, 4, 64);
        assertEquals(4, minCapacity);

        int capped = SearchConfig.computeHashCapacity(1_000_000L, 1, 4, 32);
        assertEquals(32, capped);
    }

    @Test
    void threadsAreClampedToAtLeastOne() {
        SearchConfig config = new SearchConfig();
        assertTrue(config.setThreads(0));
        assertEquals(1, config.getThreads());
        assertTrue(config.setThreads(4));
        assertEquals(4, config.getThreads());
    }
}
