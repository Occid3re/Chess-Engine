package julius.game.chessengine.engine.search.context;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Mutable state owned by a single search worker.  Each thread acquires a {@link WorkerContext}
 * from the {@link SearchContext} and reuses it across searches so that large buffers can be
 * recycled.  Whenever a new search begins the context is re-attached which clears transient
 * scratch data (SEE caches, heuristic deltas, etc.).
 */
public final class WorkerContext {

    private final int workerId;
    private final SearchContext.Heuristics heuristics;

    private final int[] moveBuffer;
    private final int[] scoreBuffer;
    private final long[] sortKeyBuffer;

    private final int[] seeKeys;
    private final int[] seeVals;
    private final int[] seeGenerations;
    private final int[] seeGenerationCounter = new int[]{0};
    private final Map<Integer, Integer> seeCache = new HashMap<>(64);

    private SplittableRandom rng;
    private long attachedSearchId = Long.MIN_VALUE;
    private int depthCapacity;

    WorkerContext(int workerId, int initialDepth) {
        this.workerId = workerId;
        int depth = Math.max(1, initialDepth);
        this.heuristics = new SearchContext.Heuristics(depth);
        this.depthCapacity = depth;

        this.moveBuffer = new int[SearchContext.MAX_MOVE_LIST_SIZE];
        this.scoreBuffer = new int[SearchContext.MAX_MOVE_LIST_SIZE];
        this.sortKeyBuffer = new long[SearchContext.MAX_MOVE_LIST_SIZE];

        this.seeKeys = new int[SearchContext.SEE_CACHE_SIZE];
        this.seeVals = new int[SearchContext.SEE_CACHE_SIZE];
        this.seeGenerations = new int[SearchContext.SEE_CACHE_SIZE];
    }

    void attachToSearch(long searchId, int requiredDepth) {
        if (attachedSearchId != searchId) {
            attachedSearchId = searchId;
            heuristics.resetUpdates();
            Arrays.fill(seeGenerations, 0);
            seeGenerationCounter[0] = 0;
            seeCache.clear();
        }
        if (requiredDepth > depthCapacity) {
            heuristics.ensureCapacity(requiredDepth);
            depthCapacity = requiredDepth;
        }
    }

    public int workerId() {
        return workerId;
    }

    public SearchContext.Heuristics heuristics() {
        return heuristics;
    }

    public int[] moveBuffer() {
        return moveBuffer;
    }

    public int[] scoreBuffer() {
        return scoreBuffer;
    }

    public long[] sortKeyBuffer() {
        return sortKeyBuffer;
    }

    public int[] seeKeys() {
        return seeKeys;
    }

    public int[] seeVals() {
        return seeVals;
    }

    public int[] seeGenerations() {
        return seeGenerations;
    }

    public int[] seeGenerationCounter() {
        return seeGenerationCounter;
    }

    public Map<Integer, Integer> seeCache() {
        return seeCache;
    }

    public SplittableRandom rng() {
        if (rng == null) {
            rng = new SplittableRandom(System.nanoTime() ^ (workerId * 0x9E3779B97F4A7C15L));
        }
        return rng;
    }

    public void seedRng(long seed) {
        rng = new SplittableRandom(seed);
    }
}
