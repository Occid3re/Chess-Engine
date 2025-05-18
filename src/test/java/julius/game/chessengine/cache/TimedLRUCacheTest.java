package julius.game.chessengine.cache;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TimedLRUCacheTest {

    @SuppressWarnings("unchecked")
    private static <K,V> Map<K, Long> getTimeMap(TimedLRUCache<K,V> cache) throws Exception {
        Field f = TimedLRUCache.class.getDeclaredField("timeMap");
        f.setAccessible(true);
        return (Map<K, Long>) f.get(cache);
    }

    @Test
    public void eldestRemovalRemovesFromTimeMap() throws Exception {
        TimedLRUCache<String, String> cache = new TimedLRUCache<>(2, 100000);
        cache.put("a", "1");
        cache.put("b", "2");
        Map<String, Long> timeMap = getTimeMap(cache);
        assertEquals(2, timeMap.size());
        cache.put("c", "3");
        assertFalse(cache.containsKey("a"));
        assertFalse(timeMap.containsKey("a"));
        assertEquals(cache.size(), timeMap.size());
    }

    @Test
    public void agedEntryRemovalCleansTimeMap() throws Exception {
        TimedLRUCache<String, String> cache = new TimedLRUCache<>(10, 50);
        cache.put("x", "1");
        Thread.sleep(60);
        cache.put("y", "2");
        Map<String, Long> timeMap = getTimeMap(cache);
        assertFalse(cache.containsKey("x"));
        assertFalse(timeMap.containsKey("x"));
    }
}
