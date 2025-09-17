package julius.game.chessengine.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Fixed-size hash table that stores a small cluster of entries for each hashed
 * index. Clusters improve replacement decisions under contention by allowing
 * the search to keep multiple candidates per bucket. The implementation relies
 * on atomic operations and is therefore safe to use from concurrent search
 * threads without additional locking.
 *
 * @param <V> type of value stored in the table
 */
public class FixedSizeTranspositionTable<V> implements TranspositionTable<V> {

    private static final int CLUSTER_SIZE = 4;

    private static final class Entry<V> {
        final long key;
        final V value;
        final int depth;
        final int age;

        Entry(long key, V value, int depth, int age) {
            this.key = key;
            this.value = value;
            this.depth = depth;
            this.age = age;
        }
    }

    private final AtomicReferenceArray<Entry<V>> table;
    private final int clusterMask;
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger currentAge = new AtomicInteger();

    public FixedSizeTranspositionTable(int capacity) {
        int clusters = 1;
        int minClusters = Math.max(1, (capacity + CLUSTER_SIZE - 1) / CLUSTER_SIZE);
        while (clusters < minClusters) {
            clusters <<= 1;
        }
        this.table = new AtomicReferenceArray<>(clusters * CLUSTER_SIZE);
        this.clusterMask = clusters - 1;
    }

    private int clusterIndex(long key) {
        return (int) key & clusterMask;
    }

    @Override
    public V get(long key) {
        int cluster = clusterIndex(key);
        int base = cluster * CLUSTER_SIZE;
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            Entry<V> entry = table.get(base + i);
            if (entry == null) {
                return null;
            }
            if (entry.key == key) {
                return entry.value;
            }
        }
        return null;
    }

    @Override
    public void put(long key, V value, int depth) {
        retry:
        while (true) {
            int cluster = clusterIndex(key);
            int base = cluster * CLUSTER_SIZE;
            Entry<V> shallowEntry = null;
            int shallowSlot = -1;
            Entry<V> oldestEntry = null;
            int oldestSlot = -1;

            for (int i = 0; i < CLUSTER_SIZE; i++) {
                int slot = base + i;
                Entry<V> current = table.get(slot);
                if (current == null) {
                    Entry<V> newEntry = new Entry<>(key, value, depth, currentAge.get());
                    if (table.compareAndSet(slot, null, newEntry)) {
                        size.incrementAndGet();
                        return;
                    } else {
                        // slot filled concurrently, restart
                        continue retry;
                    }
                }
                if (current.key == key) {
                    Entry<V> newEntry = new Entry<>(key, value, depth, currentAge.get());
                    if (table.compareAndSet(slot, current, newEntry)) {
                        return;
                    } else {
                        continue retry; // retry whole cluster
                    }
                }
                if (shallowEntry == null || current.depth < shallowEntry.depth) {
                    shallowEntry = current;
                    shallowSlot = slot;
                }
                if (oldestEntry == null || current.age < oldestEntry.age) {
                    oldestEntry = current;
                    oldestSlot = slot;
                }
            }

            if (shallowEntry != null && shallowEntry.depth < depth) {
                Entry<V> newEntry = new Entry<>(key, value, depth, currentAge.get());
                if (table.compareAndSet(shallowSlot, shallowEntry, newEntry)) {
                    return;
                }
                continue retry;
            }

            if (oldestEntry != null) {
                Entry<V> newEntry = new Entry<>(key, value, depth, currentAge.get());
                if (table.compareAndSet(oldestSlot, oldestEntry, newEntry)) {
                    return;
                }
                continue retry;
            }
        }
    }

    @Override
    public void clear() {
        for (int i = 0; i < table.length(); i++) {
            table.set(i, null);
        }
        size.set(0);
        currentAge.set(0);
    }

    @Override
    public int size() {
        return size.get();
    }

    @Override
    public void advanceAge() {
        currentAge.incrementAndGet();
    }
}
