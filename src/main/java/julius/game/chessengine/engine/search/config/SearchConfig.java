package julius.game.chessengine.engine.search.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * Mutable configuration shared across search invocations.  The engine exposes the
 * configuration through UCI options so front-ends can tune the search behaviour at
 * runtime.  Validation and clamping rules live here so the searcher only needs to
 * consume the sanitized values.
 */
public final class SearchConfig {

    public static final int MIN_HASH_SIZE_MB = 1;
    public static final int MAX_HASH_SIZE_MB = 4096;

    private volatile int maxDepth = 64;
    private volatile int hashSizeMb = 16;
    private volatile int threads = 1;
    private volatile boolean aspirationEnabled = true;
    private volatile boolean nullMovePruningEnabled = Boolean.parseBoolean(
            System.getProperty("chessengine.nullMove", "true"));
    private volatile boolean lateMoveReductionsEnabled = true;
    private volatile boolean internalIterativeReductionsEnabled = true;
    private volatile boolean futilityPruningEnabled = true;
    private volatile double contempt = 0.0;
    private volatile boolean ttConcurrency = true;
    private volatile int ttBucketsPerSet = 1;

    private final Map<String, Double> evalParams = new ConcurrentHashMap<>();

    private final Map<String, UciSpinOption> uciSpinOptions;

    public SearchConfig() {
        Map<String, UciSpinOption> options = new LinkedHashMap<>();
        options.put("Threads", new UciSpinOption(1, 1, 512, this::updateThreads));
        options.put("Hash", new UciSpinOption(hashSizeMb, MIN_HASH_SIZE_MB, MAX_HASH_SIZE_MB, this::updateHashSizeMb));
        this.uciSpinOptions = Collections.unmodifiableMap(options);
    }

    public static SearchConfig defaults() {
        return new SearchConfig();
    }

    public synchronized boolean setMaxDepth(int requestedDepth) {
        int clamped = Math.max(1, requestedDepth);
        if (clamped == this.maxDepth) {
            return false;
        }
        this.maxDepth = clamped;
        return true;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int effectiveMaxDepth() {
        return Math.max(1, maxDepth);
    }

    public synchronized boolean setThreads(int requestedThreads) {
        return updateThreads(requestedThreads);
    }

    private boolean updateThreads(int requestedThreads) {
        int clamped = Math.max(1, requestedThreads);
        if (clamped == this.threads) {
            return false;
        }
        this.threads = clamped;
        return true;
    }

    public int getThreads() {
        return threads;
    }

    public synchronized boolean setHashSizeMb(int requestedMb) {
        return updateHashSizeMb(requestedMb);
    }

    private boolean updateHashSizeMb(int requestedMb) {
        int clamped = Math.max(MIN_HASH_SIZE_MB, Math.min(MAX_HASH_SIZE_MB, requestedMb));
        if (clamped == this.hashSizeMb) {
            return false;
        }
        this.hashSizeMb = clamped;
        return true;
    }

    public int getHashSizeMb() {
        return hashSizeMb;
    }

    public boolean isAspirationEnabled() {
        return aspirationEnabled;
    }

    public void setAspirationEnabled(boolean aspirationEnabled) {
        this.aspirationEnabled = aspirationEnabled;
    }

    public boolean isNullMovePruningEnabled() {
        return nullMovePruningEnabled;
    }

    public void setNullMovePruningEnabled(boolean nullMovePruningEnabled) {
        this.nullMovePruningEnabled = nullMovePruningEnabled;
    }

    public boolean isLateMoveReductionsEnabled() {
        return lateMoveReductionsEnabled;
    }

    public void setLateMoveReductionsEnabled(boolean lateMoveReductionsEnabled) {
        this.lateMoveReductionsEnabled = lateMoveReductionsEnabled;
    }

    public boolean isInternalIterativeReductionsEnabled() {
        return internalIterativeReductionsEnabled;
    }

    public void setInternalIterativeReductionsEnabled(boolean internalIterativeReductionsEnabled) {
        this.internalIterativeReductionsEnabled = internalIterativeReductionsEnabled;
    }

    public boolean isFutilityPruningEnabled() {
        return futilityPruningEnabled;
    }

    public void setFutilityPruningEnabled(boolean futilityPruningEnabled) {
        this.futilityPruningEnabled = futilityPruningEnabled;
    }

    public double getContempt() {
        return contempt;
    }

    public void setContempt(double contempt) {
        this.contempt = contempt;
    }

    public boolean isTtConcurrencyEnabled() {
        return ttConcurrency;
    }

    public void setTtConcurrencyEnabled(boolean ttConcurrency) {
        this.ttConcurrency = ttConcurrency;
    }

    public int getTtBucketsPerSet() {
        return ttBucketsPerSet;
    }

    public void setTtBucketsPerSet(int ttBucketsPerSet) {
        this.ttBucketsPerSet = Math.max(1, ttBucketsPerSet);
    }

    public Map<String, Double> getEvalParams() {
        return Collections.unmodifiableMap(evalParams);
    }

    public void setEvalParam(String key, double value) {
        Objects.requireNonNull(key, "key");
        evalParams.put(key, value);
    }

    public void clearEvalParam(String key) {
        if (key != null) {
            evalParams.remove(key);
        }
    }

    public Map<String, UciSpinOption> getUciSpinOptions() {
        return uciSpinOptions;
    }

    public static int computeHashCapacity(long budgetBytes, int entryBytes, int minEntries, int maxEntries) {
        if (entryBytes <= 0) {
            throw new IllegalArgumentException("Entry byte estimate must be positive");
        }
        if (minEntries <= 0 || maxEntries < minEntries) {
            throw new IllegalArgumentException("Invalid entry bounds");
        }

        long estimatedEntries = Math.max(1L, budgetBytes / entryBytes);
        if (estimatedEntries > Integer.MAX_VALUE) {
            estimatedEntries = Integer.MAX_VALUE;
        }

        int candidate = (int) estimatedEntries;
        if (candidate < minEntries) {
            candidate = minEntries;
        } else if (candidate > maxEntries) {
            candidate = maxEntries;
        }

        int rounded = roundUpToPowerOfTwo(candidate);
        if (rounded < minEntries) {
            rounded = minEntries;
        }
        while (rounded > maxEntries && rounded > 1) {
            rounded >>= 1;
        }
        return rounded;
    }

    private static int roundUpToPowerOfTwo(int value) {
        if (value <= 1) {
            return 1;
        }
        int highest = Integer.highestOneBit(value);
        if (highest == value) {
            return value;
        }
        if (highest > (1 << 30)) {
            return 1 << 30;
        }
        return highest << 1;
    }

    public static final class UciSpinOption {
        private final int defaultValue;
        private final int min;
        private final int max;
        private final IntConsumer updater;

        private UciSpinOption(int defaultValue, int min, int max, IntConsumer updater) {
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.updater = Objects.requireNonNull(updater, "updater");
        }

        public int defaultValue() {
            return defaultValue;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        public void apply(int value) {
            if (value < min) {
                value = min;
            } else if (value > max) {
                value = max;
            }
            updater.accept(value);
        }
    }
}
