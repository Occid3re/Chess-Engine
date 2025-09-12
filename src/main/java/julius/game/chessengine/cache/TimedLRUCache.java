package julius.game.chessengine.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.function.BiConsumer;

/**
 * A lightweight, unsynchronized LRU cache with optional time-based eviction.
 * Keys are primitives and timestamps are stored alongside values to avoid
 * a secondary map. The cache is not thread safe; any required synchronization
 * should happen at a higher level.
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>If {@code maxAgeMs <= 0}, time-based expiry is disabled and the cache
 *       evicts only by LRU when capacity is exceeded.</li>
 *   <li>Eviction happens incrementally on {@link #put(long, Object)}, {@link #get(long)},
 *       and {@link #cleanup()} calls.</li>
 * </ul>
 *
 * @param <V> value type
 */
public class TimedLRUCache<V> {

    private static final class Entry<V> {
        final V value;
        long time;

        Entry(V value, long time) {
            this.value = value;
            this.time = time;
        }
    }

    private final int maxSize;
    private final long maxAgeMs;

    private final Long2ObjectOpenHashMap<Entry<V>> map = new Long2ObjectOpenHashMap<>();
    private final LongArrayFIFOQueue keyQueue = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue timeQueue = new LongArrayFIFOQueue();

    /**
     * @param maxSize maximum number of entries to retain (LRU when exceeded)
     * @param maxAgeMs maximum age in milliseconds before an entry is considered stale;
     *                 set {@code <= 0} to disable time-based expiry
     */
    public TimedLRUCache(int maxSize, long maxAgeMs) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        this.maxSize = maxSize;
        this.maxAgeMs = maxAgeMs;
    }

    public V get(long key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        entry.time = now;               // touch (refresh recency)
        keyQueue.enqueue(key);
        timeQueue.enqueue(now);
        evict(now);
        return entry.value;
    }

    public void put(long key, V value) {
        long now = System.currentTimeMillis();
        map.put(key, new Entry<>(value, now));
        keyQueue.enqueue(key);
        timeQueue.enqueue(now);
        evict(now);
    }

    public boolean containsKey(long key) {
        return map.containsKey(key);
    }

    public int size() {
        return map.size();
    }

    /** Manually trigger eviction pass (e.g., on timers in higher layers). */
    public void cleanup() {
        evict(System.currentTimeMillis());
    }

    /** Remove all entries. */
    public void clear() {
        map.clear();
        keyQueue.clear();
        timeQueue.clear();
    }

    public int getMaxSize() {
        return maxSize;
    }

    public long getMaxAgeMs() {
        return maxAgeMs;
    }

    private void evict(long now) {
        // Ensure both queues contain data before accessing their heads to avoid
        // NoSuchElementException when they become unsynchronized.
        while (!keyQueue.isEmpty() && !timeQueue.isEmpty()) {
            long k = keyQueue.firstLong();
            long t = timeQueue.firstLong();
            Entry<V> entry = map.get(k);

            // Drop stale queue heads or previously superseded timestamps.
            if (entry == null || entry.time != t) {
                keyQueue.dequeueLong();
                timeQueue.dequeueLong();
                continue;
            }

            boolean timeExpired = (maxAgeMs > 0) && (now - t > maxAgeMs);
            boolean sizeExceeded = map.size() > maxSize;

            if (timeExpired || sizeExceeded) {
                map.remove(k);
                keyQueue.dequeueLong();
                timeQueue.dequeueLong();
            } else {
                // Head is current and not expired; LRU is satisfied.
                break;
            }
        }

        // If the queues ever get out of sync, clear them to reset state.
        if (keyQueue.size() != timeQueue.size()) {
            keyQueue.clear();
            timeQueue.clear();
        }
    }

    public void forEach(BiConsumer<Long, V> action) {
        map.long2ObjectEntrySet().forEach(e -> action.accept(e.getLongKey(), e.getValue().value));
    }
}
