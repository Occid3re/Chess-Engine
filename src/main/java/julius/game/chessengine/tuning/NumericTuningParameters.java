package julius.game.chessengine.tuning;

import java.util.Map;

/**
 * Central registry for numeric tuning parameters. Supports thread-local overrides so that
 * independent searches can evaluate different parameter sets concurrently.
 */
public final class NumericTuningParameters {

    private static final ParameterSnapshot DEFAULT_SNAPSHOT = ParameterSnapshot.defaultSnapshot();
    private static final Map<String, Double> DEFAULT_CACHE = DEFAULT_SNAPSHOT.asMap();

    private static volatile Map<String, Double> GLOBAL_PARAMETERS = Map.of();

    private NumericTuningParameters() {
    }

    static synchronized int registerDefault(String key, double defaultValue) {
        ParamId id = ParamId.forKey(key);
        if (id == null) {
            throw new IllegalArgumentException("Unknown parameter key: " + key);
        }
        if (Double.doubleToLongBits(id.defaultValue()) != Double.doubleToLongBits(defaultValue)) {
            throw new IllegalArgumentException(
                    "Default value mismatch for " + key + ": expected " + id.defaultValue() + " but was " + defaultValue);
        }
        return id.ordinal();
    }

    static double resolve(int index, double defaultValue) {
        ParamId[] ids = ParamId.values();
        if (index < 0 || index >= ids.length) {
            return defaultValue;
        }
        return ParameterRegistry.get(ids[index]);
    }

    static double resolve(String key, double defaultValue) {
        String normalized = normalizeKey(key);
        ParamId id = ParamId.forKey(normalized);
        if (id != null) {
            return ParameterRegistry.get(id);
        }
        Double extra = ParameterRegistry.lookupExtra(normalized);
        if (extra != null) {
            return extra;
        }
        Double fallback = DEFAULT_CACHE.get(normalized);
        return fallback != null ? fallback : defaultValue;
    }

    static Map<String, Double> defaults() {
        return DEFAULT_CACHE;
    }

    public static AutoCloseable use(Map<String, Double> parameters) {
        Map<String, Double> normalized = ParameterNormalizer.normalize(parameters);
        ParameterSnapshot previous = ParameterRegistry.threadSnapshot();
        if (normalized.isEmpty()) {
            ParameterRegistry.clearThreadSnapshot();
        } else {
            ParameterSnapshot base = ParameterRegistry.globalSnapshot();
            ParameterSnapshot overrides = ParameterSnapshot.apply(base, normalized);
            if (overrides == base) {
                ParameterRegistry.clearThreadSnapshot();
            } else {
                ParameterRegistry.setThreadSnapshot(overrides);
            }
        }
        return () -> restore(previous);
    }

    private static void restore(ParameterSnapshot previous) {
        if (previous == null) {
            ParameterRegistry.clearThreadSnapshot();
        } else {
            ParameterRegistry.setThreadSnapshot(previous);
        }
    }

    public static void setGlobal(Map<String, Double> parameters) {
        Map<String, Double> normalized = ParameterNormalizer.normalize(parameters);
        GLOBAL_PARAMETERS = normalized;
        ParameterSnapshot snapshot = ParameterSnapshot.apply(DEFAULT_SNAPSHOT, normalized);
        ParameterRegistry.setGlobalSnapshot(snapshot);
        Tuning.refresh();
    }

    public static void hotReload(Map<String, Double> parameters) {
        setGlobal(parameters);
    }

    static String normalizeKey(String key) {
        return ParameterNormalizer.normalizeKey(key);
    }
}
