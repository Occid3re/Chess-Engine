package julius.game.chessengine.ai;

import julius.game.chessengine.tuning.AiTuning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes sensible defaults for the engine's concurrency knobs when they are
 * not explicitly provided via system properties. The planner keeps the current
 * behaviour for any property that is already set.
 */
public final class SearchConcurrencyPlanner {

    private static final Logger LOG = LoggerFactory.getLogger(SearchConcurrencyPlanner.class);

    private static final String PROP_SEARCH_THREADS = "chessengine.searchThreads";
    private static final String PROP_LAZY_THREADS = "chessengine.lazySmpThreads";
    private static final String PROP_ROOT_LIMIT = "chessengine.rootParallelLimit";
    private static final String PROP_TT_MB = "chessengine.tt.mb";

    private SearchConcurrencyPlanner() {
    }

    public static Plan resolve() {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());

        Property searchThreadsProp = readIntProperty(PROP_SEARCH_THREADS);
        Property lazyThreadsProp = readIntProperty(PROP_LAZY_THREADS);
        Property rootLimitProp = readIntProperty(PROP_ROOT_LIMIT);
        Property ttProp = readIntProperty(PROP_TT_MB);

        int defaultSearch = available >= 2
                ? Math.max(2, (int) Math.floor(available * 0.67d))
                : 1;
        defaultSearch = Math.min(defaultSearch, available);

        int searchThreads = searchThreadsProp.valueOr(defaultSearch);
        searchThreads = clamp(searchThreads, 1, available);

        int defaultLazy = Math.max(1, searchThreads / 2);
        int lazyThreads = lazyThreadsProp.valueOr(defaultLazy);
        lazyThreads = clamp(lazyThreads, 1, searchThreads);

        int defaultRootLimit = Math.max(2, available * 3);
        int rootLimit = rootLimitProp.valueOr(defaultRootLimit);
        rootLimit = Math.max(1, rootLimit);

        int defaultTtMb = 256;
        int ttMb = ttProp.valueOr(defaultTtMb);
        ttMb = clamp(ttMb, 1, AiTuning.MAX_HASH_SIZE_MB);

        Plan plan = new Plan(
                available,
                searchThreads,
                searchThreadsProp.wasExplicit(),
                lazyThreads,
                lazyThreadsProp.wasExplicit(),
                rootLimit,
                rootLimitProp.wasExplicit(),
                ttMb,
                ttProp.wasExplicit()
        );

        logPlan(plan);
        plan.seedSystemProperties();
        return plan;
    }

    private static void logPlan(Plan plan) {
        if (LOG.isInfoEnabled()) {
            LOG.info("Search concurrency plan: availableProcessors={}, searchThreads={}{}" +
                            ", lazySmpThreads={}{}" +
                            ", rootParallelLimit={}{}" +
                            ", ttMb={}{}",
                    plan.availableProcessors(),
                    plan.searchThreads(),
                    plan.searchThreadsUserDefined() ? " (user)" : "",
                    plan.lazySmpThreads(),
                    plan.lazySmpThreadsUserDefined() ? " (user)" : "",
                    plan.rootParallelLimit(),
                    plan.rootParallelLimitUserDefined() ? " (user)" : "",
                    plan.transpositionTableMb(),
                    plan.transpositionTableUserDefined() ? " (user)" : "");
        }
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static Property readIntProperty(String key) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return Property.absent();
        }
        try {
            return Property.explicit(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ex) {
            LOG.warn("Ignoring malformed integer value '{}' for property {}", raw, key);
            return Property.absent();
        }
    }

    public static record Plan(int availableProcessors,
                               int searchThreads,
                               boolean searchThreadsUserDefined,
                               int lazySmpThreads,
                               boolean lazySmpThreadsUserDefined,
                               int rootParallelLimit,
                               boolean rootParallelLimitUserDefined,
                               int transpositionTableMb,
                               boolean transpositionTableUserDefined) {

        void seedSystemProperties() {
            if (!searchThreadsUserDefined) {
                System.setProperty(PROP_SEARCH_THREADS, Integer.toString(searchThreads));
            }
            if (!lazySmpThreadsUserDefined) {
                System.setProperty(PROP_LAZY_THREADS, Integer.toString(lazySmpThreads));
            }
            if (!rootParallelLimitUserDefined) {
                System.setProperty(PROP_ROOT_LIMIT, Integer.toString(rootParallelLimit));
            }
            if (!transpositionTableUserDefined) {
                System.setProperty(PROP_TT_MB, Integer.toString(transpositionTableMb));
            }
        }
    }

    private record Property(Integer value, boolean wasExplicit) {
        static Property absent() {
            return new Property(null, false);
        }

        static Property explicit(int value) {
            return new Property(value, true);
        }

        int valueOr(int defaultValue) {
            return value != null ? value : defaultValue;
        }
    }
}
