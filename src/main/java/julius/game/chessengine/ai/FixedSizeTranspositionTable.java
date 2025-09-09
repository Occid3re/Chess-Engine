package julius.game.chessengine.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Simple fixed-size hash table with open addressing. The table uses linear
 * probing and overwrites old entries when the table is full. The implementation
 * is thread-safe using atomic operations which makes it usable in concurrent
 * search scenarios without additional locking. Keys are {@code long} hashes
 * representing board states.
 *
 * @param <V> type of value stored in the table
 */
public class FixedSizeTranspositionTable<V> implements TranspositionTable<V> {

    private static final class Entry<V> {
        final long key;
        final V value;

        Entry(long key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final AtomicReferenceArray<Entry<V>> table;
    private final int mask; // table.length - 1 (power of two size)
    private final AtomicInteger size = new AtomicInteger();

    public FixedSizeTranspositionTable(int capacity) {
        int n = 1;
        while (n < capacity) {
            n <<= 1;
        }
        this.table = new AtomicReferenceArray<>(n);
        this.mask = n - 1;
    }

    private int index(long key) {
        return (int) (key) & mask;
    }

    @Override
    public V get(long key) {
        int idx = index(key);
        for (int i = 0; i < table.length(); i++) {
            Entry<V> e = table.get(idx);
            if (e == null) {
                return null;
            }
            if (e.key == key) {
                return e.value;
            }
            idx = (idx + 1) & mask;
        }
        return null;
    }

    @Override
    public void put(long key, V value) {
        int idx = index(key);
        Entry<V> newEntry = new Entry<>(key, value);
        for (int i = 0; i < table.length(); i++) {
            Entry<V> cur = table.get(idx);
            if (cur == null) {
                if (table.compareAndSet(idx, null, newEntry)) {
                    size.incrementAndGet();
                    return;
                } else {
                    continue; // CAS failed, retry same slot
                }
            } else if (cur.key == key) {
                table.set(idx, newEntry);
                return;
            } else {
                idx = (idx + 1) & mask;
            }
        }
        // table full, overwrite the initial slot
        table.set(idx, newEntry);
    }

    @Override
    public void clear() {
        for (int i = 0; i < table.length(); i++) {
            table.set(i, null);
        }
        size.set(0);
    }

    @Override
    public int size() {
        return size.get();
    }
}
