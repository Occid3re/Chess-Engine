package julius.game.chessengine.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * A lightweight LRU cache with optional time-based eviction. Keys are
 * primitives and timestamps are stored alongside values to avoid a secondary
 * map. Basic thread-safety is provided via an internal lock so callers can
 * access the cache concurrently without additional synchronization.
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>If {@code maxAgeMs <= 0}, time-based expiry is disabled and the cache
 *       evicts only by LRU when capacity is exceeded.</li>
 *   <li>Eviction happens incrementally on {@link #put(long, Object)}, {@link #get(long)},
 *       and {@link #cleanup()} calls.</li>
 *   <li>The backing hash map is sized up-front using a default load factor of
 *       approximately {@value #DEFAULT_LOAD_FACTOR} to avoid repeated rehashes
 *       during warm-up. Use {@link #TimedLRUCache(int, long, float)} to override
 *       the load factor if a different density is required.</li>
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

    private static final float DEFAULT_LOAD_FACTOR = 0.75f;

    private final int maxSize;
    private final long maxAgeMs;

    private final Long2ObjectOpenHashMap<Entry<V>> map;
    private final LongArrayFIFOQueue keyQueue = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue timeQueue = new LongArrayFIFOQueue();
    private final Object lock = new Object();

    /**
     * Uses the default load factor of approximately {@value #DEFAULT_LOAD_FACTOR}.
     *
     * @param maxSize maximum number of entries to retain (LRU when exceeded)
     * @param maxAgeMs maximum age in milliseconds before an entry is considered stale;
     *                 set {@code <= 0} to disable time-based expiry
     */
    public TimedLRUCache(int maxSize, long maxAgeMs) {
        this(maxSize, maxAgeMs, DEFAULT_LOAD_FACTOR);
    }

    /**
     * @param maxSize maximum number of entries to retain (LRU when exceeded)
     * @param maxAgeMs maximum age in milliseconds before an entry is considered stale;
     *                 set {@code <= 0} to disable time-based expiry
     * @param loadFactor desired load factor for the underlying open-addressed hash map;
     *                   defaults to approximately {@value #DEFAULT_LOAD_FACTOR}
     */
    public TimedLRUCache(int maxSize, long maxAgeMs, float loadFactor) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        if (!(loadFactor > 0.0f && loadFactor < 1.0f)) {
            throw new IllegalArgumentException("loadFactor must be within (0, 1)");
        }
        this.maxSize = maxSize;
        this.maxAgeMs = maxAgeMs;
        int expectedEntries = sanitizeExpectedSize(maxSize);
        // fastutil will expand to the nearest power-of-two >= expected/loadFactor.
        this.map = new Long2ObjectOpenHashMap<>(expectedEntries, loadFactor);
    }

    public V get(long key) {
        synchronized (lock) {
            Entry<V> entry = map.get(key);
            if (entry == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            entry.time = now;               // touch (refresh recency)
            keyQueue.enqueue(key);
            timeQueue.enqueue(now);
            evictLocked(now);
            return entry.value;
        }
    }

    public void put(long key, V value) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            map.put(key, new Entry<>(value, now));
            keyQueue.enqueue(key);
            timeQueue.enqueue(now);
            evictLocked(now);
        }
    }

    public boolean containsKey(long key) {
        synchronized (lock) {
            return map.containsKey(key);
        }
    }

    public int size() {
        synchronized (lock) {
            return map.size();
        }
    }

    /** Manually trigger eviction pass (e.g., on timers in higher layers). */
    public void cleanup() {
        synchronized (lock) {
            evictLocked(System.currentTimeMillis());
        }
    }

    /** Remove all entries. */
    public void clear() {
        synchronized (lock) {
            map.clear();
            keyQueue.clear();
            timeQueue.clear();
        }
    }

    public int getMaxSize() {
        return maxSize;
    }

    public long getMaxAgeMs() {
        return maxAgeMs;
    }

    private void evictLocked(long now) {
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

    private static int sanitizeExpectedSize(int maxSize) {
        long sanitized = Math.max(2L, maxSize);
        return (int) Math.min(Integer.MAX_VALUE - 8L, sanitized);
    }

    public void forEach(BiConsumer<Long, V> action) {
        Objects.requireNonNull(action, "action");
        List<AbstractMap.SimpleEntry<Long, V>> snapshot = new ArrayList<>();
        synchronized (lock) {
            map.long2ObjectEntrySet().fastForEach(e ->
                    snapshot.add(new AbstractMap.SimpleEntry<>(e.getLongKey(), e.getValue().value))
            );
        }
        for (AbstractMap.SimpleEntry<Long, V> entry : snapshot) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }
}
