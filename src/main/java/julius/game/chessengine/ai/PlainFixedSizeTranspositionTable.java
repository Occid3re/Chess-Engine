package julius.game.chessengine.ai;

import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * Fixed-size transposition table implementation for single-threaded search.
 * It uses plain arrays and does not perform any atomic operations.
 *
 * @param <V> value type stored in the table
 */
public class PlainFixedSizeTranspositionTable<V> implements TranspositionTable<V> {

    private final long[] keys;
    private final V[] values;
    private final int mask; // table.length - 1 (power of two size)
    private int size;

    @SuppressWarnings("unchecked")
    public PlainFixedSizeTranspositionTable(int capacity, Class<V> valueClass) {
        int n = 1;
        while (n < capacity) {
            n <<= 1;
        }
        this.keys = new long[n];
        this.values = (V[]) Array.newInstance(valueClass, n);
        this.mask = n - 1;
    }

    private int index(long key) {
        return (int) key & mask;
    }

    @Override
    public V get(long key) {
        int idx = index(key);
        for (int i = 0; i < values.length; i++) {
            V v = values[idx];
            if (v == null) {
                return null;
            }
            if (keys[idx] == key) {
                return v;
            }
            idx = (idx + 1) & mask;
        }
        return null;
    }

    @Override
    public void put(long key, V value) {
        int idx = index(key);
        for (int i = 0; i < values.length; i++) {
            V v = values[idx];
            if (v == null) {
                keys[idx] = key;
                values[idx] = value;
                size++;
                return;
            } else if (keys[idx] == key) {
                values[idx] = value;
                return;
            } else {
                idx = (idx + 1) & mask;
            }
        }
        // table full, overwrite the initial slot
        keys[idx] = key;
        values[idx] = value;
    }

    @Override
    public void clear() {
        Arrays.fill(values, null);
        Arrays.fill(keys, 0L);
        size = 0;
    }

    @Override
    public int size() {
        return size;
    }
}
