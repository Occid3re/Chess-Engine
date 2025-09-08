package julius.game.chessengine.cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A cache implementation that combines Least Recently Used (LRU) and timed eviction policies.
 * Entries in this cache are automatically removed when they become older than a specified age or
 * when the cache exceeds a specified size.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
public class TimedLRUCache<K, V> extends LinkedHashMap<K, V> {
    private final long maxAge;
    private final int maxSize;
    private final Map<K, Long> timeMap = new ConcurrentHashMap<>();

    /**
     * Constructs an empty TimedLRUCache with the specified maximum size and maximum age for entries.
     *
     * @param maxSize the maximum number of entries this cache can hold.
     * @param maxAge  the maximum time (in milliseconds) an entry can stay in the cache before it is eligible for removal.
     */
    public TimedLRUCache(int maxSize, long maxAge) {
        super(16, 0.75f, true); // Initialize LinkedHashMap with access order
        this.maxSize = maxSize;
        this.maxAge = maxAge;
    }

    /**
     * Determines whether the eldest entry should be removed or not. An entry is removed if
     * the cache size exceeds the maximum size or if the entry's age exceeds the maximum age.
     *
     * @param eldest the eldest entry in the cache
     * @return true if the eldest entry should be removed; false otherwise
     */
    @Override
    protected synchronized boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        Long addedAt = timeMap.get(eldest.getKey());
        if (addedAt == null) {
            return false; // should not happen, but keep the entry if we have no timestamp
        }
        boolean remove = size() > maxSize || System.currentTimeMillis() - addedAt > maxAge;
        if (remove) {
            timeMap.remove(eldest.getKey());
        }
        return remove;
    }
    /**
     * Associates the specified value with the specified key in this cache. If the cache previously
     * contained a mapping for the key, the old value is replaced. The current time is recorded for
     * the key for timed eviction purposes.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the key, or null if there was no mapping for the key
     */
    @Override
    public synchronized V put(K key, V value) {
        timeMap.put(key, System.currentTimeMillis());
        return super.put(key, value);
    }

    /**
     * Retrieves a value from the cache. Accessing an entry updates its
     * position in the LRU order.
     *
     * @param key key whose associated value is to be returned
     * @return the value associated with the specified key, or {@code null}
     *         if the cache contains no mapping for the key
     */
    @Override
    public synchronized V get(Object key) {
        return super.get(key);
    }

    /**
     * Checks whether a key is present in the cache.
     *
     * @param key key whose presence in this cache is to be tested
     * @return {@code true} if this cache contains a mapping for the
     *         specified key
     */
    @Override
    public synchronized boolean containsKey(Object key) {
        return super.containsKey(key);
    }

    /**
     * Returns the number of key-value mappings in this cache.
     *
     * @return the number of entries in the cache
     */
    @Override
    public synchronized int size() {
        return super.size();
    }

    /**
     * Cleans up the cache by removing entries that are older than the maximum allowed age.
     * This method should be called periodically to ensure timely removal of old entries.
     */
    public synchronized void cleanup() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<K, Long>> it = timeMap.entrySet().iterator(); it.hasNext();) {
            Map.Entry<K, Long> entry = it.next();
            if (now - entry.getValue() > maxAge) {
                it.remove();
                super.remove(entry.getKey());
            }
        }
    }

}
