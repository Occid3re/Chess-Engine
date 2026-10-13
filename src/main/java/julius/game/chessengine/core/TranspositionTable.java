package julius.game.chessengine.core;

/**
 * Shared lock-free transposition table. Each entry is two longs: (key ^ data, data), so torn writes from
 * concurrent threads are detected on read. Four entries form a 64-byte bucket.
 *
 * <p>Data layout: move 16 | score 16 | eval 16 | depth 8 | bound 2 | age 6.</p>
 */
public final class TranspositionTable {

    public static final int BOUND_NONE = 0, BOUND_UPPER = 1, BOUND_LOWER = 2, BOUND_EXACT = 3;
    public static final int VALUE_NONE = 32001;
    private static final int BUCKET = 4;
    private static final int DEPTH_OFFSET = 8;

    private long[] table;
    private long buckets;
    private int age;

    public TranspositionTable(int megabytes) {
        resize(megabytes);
    }

    public synchronized void resize(int megabytes) {
        long bytes = Math.max(1, megabytes) * 1024L * 1024L;
        long entries = bytes / 16;
        buckets = Math.max(1, entries / BUCKET);
        table = new long[(int) Math.min(Integer.MAX_VALUE - 8, buckets * BUCKET * 2)];
        buckets = table.length / (BUCKET * 2);
        age = 0;
    }

    public void clear() {
        java.util.Arrays.fill(table, 0L);
        age = 0;
    }

    public void newSearch() {
        age = (age + 1) & 63;
    }

    private int bucketIndex(long key) {
        return (int) Math.unsignedMultiplyHigh(key, buckets) * (BUCKET * 2);
    }

    public static int move(long data) {
        return (int) (data & 0xFFFF);
    }

    public static int score(long data) {
        return (short) (data >>> 16);
    }

    public static int eval(long data) {
        return (short) (data >>> 32);
    }

    public static int depth(long data) {
        return (int) ((data >>> 48) & 0xFF) - DEPTH_OFFSET;
    }

    public static int bound(long data) {
        return (int) ((data >>> 56) & 3);
    }

    private static int entryAge(long data) {
        return (int) (data >>> 58);
    }

    /** Returns the entry data for key, or 0 if absent. A stored entry always has a nonzero data word. */
    public long probe(long key) {
        long[] t = table;
        int base = bucketIndex(key);
        for (int i = 0; i < BUCKET; i++) {
            int idx = base + 2 * i;
            long data = t[idx + 1];
            if ((t[idx] ^ data) == key && data != 0L) return data;
        }
        return 0L;
    }

    public void store(long key, int move, int score, int eval, int depth, int bound) {
        long[] t = table;
        int base = bucketIndex(key);
        int replace = base;
        int worst = Integer.MAX_VALUE;
        for (int i = 0; i < BUCKET; i++) {
            int idx = base + 2 * i;
            long data = t[idx + 1];
            if ((t[idx] ^ data) == key && data != 0L) {
                // Same position: keep the old move if we have none, and avoid overwriting deeper exact info
                if (move == Move.NONE) move = move(data);
                if (bound != BOUND_EXACT && depth + 3 < depth(data) && entryAge(data) == age) return;
                replace = idx;
                worst = Integer.MIN_VALUE;
                break;
            }
            int ageDiff = (age - entryAge(data)) & 63;
            int value = data == 0L ? Integer.MIN_VALUE + 1 : depth(data) - 8 * ageDiff;
            if (value < worst) {
                worst = value;
                replace = idx;
            }
        }
        long data = (move & 0xFFFFL)
                | ((score & 0xFFFFL) << 16)
                | ((eval & 0xFFFFL) << 32)
                | (((long) (depth + DEPTH_OFFSET) & 0xFF) << 48)
                | ((long) (bound & 3) << 56)
                | ((long) age << 58);
        if (data == 0L) data = 1L << 58 | 1L; // never store the "empty" marker
        t[replace] = key ^ data;
        t[replace + 1] = data;
    }

    /** Permille of sampled entries written in the current search generation. */
    public int hashfull() {
        int used = 0;
        int n = Math.min(1000, table.length / 2);
        for (int i = 0; i < n; i++) {
            long data = table[2 * i + 1];
            if (data != 0L && entryAge(data) == age) used++;
        }
        return n == 0 ? 0 : used * 1000 / n;
    }
}
