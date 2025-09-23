package julius.game.chessengine.ai;

import java.util.Arrays;

/**
 * Fixed-size transposition table implementation for single-threaded search.
 * Each hashed index owns a small cluster of entries so the search can keep
 * several candidates per bucket and make informed replacement decisions.
 *
 * @param <V> value type stored in the table
 */
public class PlainFixedSizeTranspositionTable<V> implements TranspositionTable<V> {

    private static final int CLUSTER_SIZE = 4;

    private record Entry<V>(long key, V value, int depth, int age) {
    }

    private final Entry<V>[] table;
    private final int clusterMask; // number of clusters - 1
    private int size;
    private int currentAge;

    @SuppressWarnings("unchecked")
    public PlainFixedSizeTranspositionTable(int capacity) {
        int clusters = 1;
        int minClusters = Math.max(1, (capacity + CLUSTER_SIZE - 1) / CLUSTER_SIZE);
        while (clusters < minClusters) {
            clusters <<= 1;
        }
        this.table = (Entry<V>[]) new Entry[clusters * CLUSTER_SIZE];
        this.clusterMask = clusters - 1;
    }

    private int index(long key) {
        return (int) key & clusterMask;
    }

    @Override
    public V get(long key) {
        int cluster = index(key);
        int base = cluster * CLUSTER_SIZE;
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            Entry<V> entry = table[base + i];
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
        int cluster = index(key);
        int base = cluster * CLUSTER_SIZE;
        Entry<V> shallowEntry = null;
        int shallowSlot = -1;
        Entry<V> oldestEntry = null;
        int oldestSlot = -1;

        for (int i = 0; i < CLUSTER_SIZE; i++) {
            int slot = base + i;
            Entry<V> current = table[slot];
            if (current == null) {
                table[slot] = new Entry<>(key, value, depth, currentAge);
                size++;
                return;
            }
            if (current.key == key) {
                table[slot] = new Entry<>(key, value, depth, currentAge);
                return;
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

        if (shallowEntry.depth < depth) {
            table[shallowSlot] = new Entry<>(key, value, depth, currentAge);
            return;
        }

        table[oldestSlot] = new Entry<>(key, value, depth, currentAge);
    }

    @Override
    public void clear() {
        Arrays.fill(table, null);
        size = 0;
        currentAge = 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void advanceAge() {
        currentAge++;
    }
}
