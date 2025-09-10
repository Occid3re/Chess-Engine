package julius.game.chessengine.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.function.BiConsumer;

/**
 * A lightweight, unsynchronized LRU cache with time based eviction. Keys are primitives and
 * timestamps are stored alongside values to avoid a secondary map. The cache is not thread safe and
 * any required synchronization should happen at a higher level.
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
    private final long maxAge;

    private final Long2ObjectOpenHashMap<Entry<V>> map = new Long2ObjectOpenHashMap<>();
    private final LongArrayFIFOQueue keyQueue = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue timeQueue = new LongArrayFIFOQueue();

    public TimedLRUCache(int maxSize, long maxAge) {
        this.maxSize = maxSize;
        this.maxAge = maxAge;
    }

    public V get(long key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        entry.time = now;
        keyQueue.enqueue(key);
        timeQueue.enqueue(now);
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

    public void cleanup() {
        evict(System.currentTimeMillis());
    }

    private void evict(long now) {
        // Ensure both queues contain data before accessing their heads to avoid
        // NoSuchElementException when they become unsynchronized.
        while (!keyQueue.isEmpty() && !timeQueue.isEmpty()) {
            long k = keyQueue.firstLong();
            long t = timeQueue.firstLong();
            Entry<V> entry = map.get(k);
            if (entry == null || entry.time != t) {
                keyQueue.dequeueLong();
                timeQueue.dequeueLong();
                continue;
            }
            if (now - t > maxAge || map.size() > maxSize) {
                map.remove(k);
                keyQueue.dequeueLong();
                timeQueue.dequeueLong();
            } else {
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

