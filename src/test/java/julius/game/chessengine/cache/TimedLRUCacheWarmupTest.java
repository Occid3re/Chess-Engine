package julius.game.chessengine.cache;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TimedLRUCacheWarmupTest {

    @Test
    void warmupDoesNotTriggerRehashes() throws Exception {
        int maxSize = 4096;
        TimedLRUCache<Object> cache = new TimedLRUCache<>(maxSize, 0);

        Field mapField = TimedLRUCache.class.getDeclaredField("map");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Long2ObjectOpenHashMap<?> map = (Long2ObjectOpenHashMap<?>) mapField.get(cache);

        Field capacityField = Long2ObjectOpenHashMap.class.getDeclaredField("n");
        capacityField.setAccessible(true);

        Field loadFactorField = Long2ObjectOpenHashMap.class.getDeclaredField("f");
        loadFactorField.setAccessible(true);
        float loadFactor = loadFactorField.getFloat(map);

        int previousCapacity = capacityField.getInt(map);
        int expectedCapacity = HashCommon.arraySize(maxSize, loadFactor);
        assertEquals(expectedCapacity, previousCapacity,
                "Map should be pre-sized to avoid rehashing during warm-up");
        int rehashCount = 0;

        for (int i = 0; i < maxSize; i++) {
            cache.put(i, new Object());
            int currentCapacity = capacityField.getInt(map);
            if (currentCapacity != previousCapacity) {
                rehashCount++;
                previousCapacity = currentCapacity;
            }
        }

        assertEquals(0, rehashCount,
                "Expected no rehash operations during warm-up inserts but observed " + rehashCount);
    }
}
