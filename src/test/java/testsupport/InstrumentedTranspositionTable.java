package testsupport;

import julius.game.chessengine.ai.TranspositionTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight {@link TranspositionTable} implementation that records method invocation counts.
 * Tests can inject this table to observe the interaction pattern without affecting production code.
 */
public final class InstrumentedTranspositionTable<V> implements TranspositionTable<V> {

    private static final Logger log = LogManager.getLogger(InstrumentedTranspositionTable.class);


    private final Map<Long, V> store = new ConcurrentHashMap<>();
    private final AtomicInteger getCount = new AtomicInteger();
    private final AtomicInteger putCount = new AtomicInteger();
    private final AtomicInteger clearCount = new AtomicInteger();
    private final AtomicInteger advanceAgeCount = new AtomicInteger();

    @Override
    public V get(long key) {
        int count = getCount.incrementAndGet();
        log.debug("Instrumented TT get #{} -> {}", count, key);
        return store.get(key);
    }

    @Override
    public void put(long key, V value, int depth) {
        int count = putCount.incrementAndGet();
        log.debug("Instrumented TT put #{} -> key {}, depth {}", count, key, depth);
        store.put(key, value);
    }

    @Override
    public void clear() {
        int count = clearCount.incrementAndGet();
        log.debug("Instrumented TT clear #{}", count);
        store.clear();
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void advanceAge() {
        int count = advanceAgeCount.incrementAndGet();
        log.debug("Instrumented TT advanceAge #{}", count);
    }

    public Snapshot snapshot() {
        return new Snapshot(getCount.get(), putCount.get(), clearCount.get(), advanceAgeCount.get(), store.size());
    }

    public void resetCounters() {
        getCount.set(0);
        putCount.set(0);
        clearCount.set(0);
        advanceAgeCount.set(0);
    }

    public record Snapshot(int getCount, int putCount, int clearCount, int advanceAgeCount, int size) {
    }
}

