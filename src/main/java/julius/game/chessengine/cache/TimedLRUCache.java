package julius.game.chessengine.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
    private final Object lock = new Object();
    private volatile Consumer<V> evictionListener = v -> {};

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
        List<V> evicted = Collections.emptyList();
        V value;
        synchronized (lock) {
            Entry<V> entry = map.get(key);
            if (entry == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            entry.time = now;               // touch (refresh recency)
            keyQueue.enqueue(key);
            timeQueue.enqueue(now);
            evicted = evictLocked(now);
            value = entry.value;
        }
        notifyEvicted(evicted);
        return value;
    }

    public void put(long key, V value) {
        long now = System.currentTimeMillis();
        Entry<V> previous;
        List<V> evicted = Collections.emptyList();
        synchronized (lock) {
            previous = map.put(key, new Entry<>(value, now));
            keyQueue.enqueue(key);
            timeQueue.enqueue(now);
            evicted = evictLocked(now);
        }
        if (previous != null) {
            notifyEvicted(previous.value);
        }
        notifyEvicted(evicted);
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
        List<V> evicted;
        synchronized (lock) {
            evicted = evictLocked(System.currentTimeMillis());
        }
        notifyEvicted(evicted);
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

    private List<V> evictLocked(long now) {
        List<V> evicted = Collections.emptyList();
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
                if (entry != null) {
                    if (evicted.isEmpty()) {
                        evicted = new ArrayList<>();
                    }
                    evicted.add(entry.value);
                }
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
        return evicted;
    }

    private void notifyEvicted(List<V> evicted) {
        if (evicted == null || evicted.isEmpty()) {
            return;
        }
        Consumer<V> listener = evictionListener;
        for (V value : evicted) {
            try {
                listener.accept(value);
            } catch (RuntimeException ignored) {
                // Listener exceptions should not break cache behaviour.
            }
        }
    }

    private void notifyEvicted(V value) {
        if (value == null) {
            return;
        }
        Consumer<V> listener = evictionListener;
        try {
            listener.accept(value);
        } catch (RuntimeException ignored) {
            // Listener exceptions should not break cache behaviour.
        }
    }

    public void setEvictionListener(Consumer<V> evictionListener) {
        this.evictionListener = (evictionListener != null) ? evictionListener : v -> {};
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
