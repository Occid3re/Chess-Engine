package julius.game.chessengine.tuning;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for numeric tuning parameters. Supports thread-local overrides so that
 * independent searches can evaluate different parameter sets concurrently.
 */
public final class NumericTuningParameters {

    private static final Map<String, Double> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, Integer> INDEX_BY_KEY = new ConcurrentHashMap<>();
    private static final ThreadLocal<double[]> THREAD_PARAMETERS = new ThreadLocal<>();
    private static volatile Map<String, Double> GLOBAL_PARAMETERS = Map.of();
    private static volatile Map<String, Double> DEFAULT_CACHE = Map.of();
    private static volatile double[] GLOBAL_CACHE = new double[0];

    private static volatile double[] DEFAULT_VALUES = new double[32];
    private static volatile int PARAMETER_COUNT;

    private NumericTuningParameters() {
    }

    static synchronized int registerDefault(String key, double defaultValue) {
        String normalized = normalizeKey(key);
        Integer existing = INDEX_BY_KEY.get(normalized);
        if (existing != null) {
            return existing;
        }

        int index = PARAMETER_COUNT;
        int newCount = index + 1;
        INDEX_BY_KEY.put(normalized, index);
        ensureCapacity(newCount);
        DEFAULTS.put(normalized, defaultValue);
        DEFAULT_VALUES[index] = defaultValue;
        DEFAULT_CACHE = Collections.unmodifiableMap(new LinkedHashMap<>(DEFAULTS));

        double[] updatedGlobal = copyAndExtend(GLOBAL_CACHE, newCount);
        Double override = GLOBAL_PARAMETERS.get(normalized);
        updatedGlobal[index] = (override != null) ? override : Double.NaN;
        GLOBAL_CACHE = updatedGlobal;
        PARAMETER_COUNT = newCount;
        return index;
    }

    static double resolve(String key, double defaultValue) {
        Objects.requireNonNull(key, "key");
        String normalized = normalizeKey(key);
        Integer index = INDEX_BY_KEY.get(normalized);
        if (index == null) {
            Double fallback = DEFAULT_CACHE.get(normalized);
            return fallback != null ? fallback : defaultValue;
        }
        return resolve(index, defaultValue);
    }

    static double resolve(int index, double defaultValue) {
        double[] local = THREAD_PARAMETERS.get();
        if (local != null && index < local.length) {
            double value = local[index];
            if (!Double.isNaN(value)) {
                return value;
            }
        }

        double[] global = GLOBAL_CACHE;
        if (index < global.length) {
            double value = global[index];
            if (!Double.isNaN(value)) {
                return value;
            }
        }

        int count = PARAMETER_COUNT;
        if (index < count) {
            double[] defaults = DEFAULT_VALUES;
            double value = defaults[index];
            return !Double.isNaN(value) ? value : defaultValue;
        }
        return defaultValue;
    }

    static Map<String, Double> defaults() {
        return DEFAULT_CACHE;
    }

    public static AutoCloseable use(Map<String, Double> parameters) {
        Map<String, Double> normalized = normalize(parameters);
        double[] overrides = buildOverrideArray(normalized);
        double[] previous = THREAD_PARAMETERS.get();
        THREAD_PARAMETERS.set(overrides);
        return () -> {
            if (previous == null) {
                THREAD_PARAMETERS.remove();
            } else {
                THREAD_PARAMETERS.set(previous);
            }
        };
    }

    public static void setGlobal(Map<String, Double> parameters) {
        GLOBAL_PARAMETERS = normalize(parameters);
        GLOBAL_CACHE = buildOverrideArray(GLOBAL_PARAMETERS);
    }

    private static Map<String, Double> normalize(Map<String, Double> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isNaN()) {
                return;
            }
            normalized.put(normalizeKey(key), value);
        });
        return Collections.unmodifiableMap(normalized);
    }

    private static double[] buildOverrideArray(Map<String, Double> parameters) {
        int size = PARAMETER_COUNT;
        double[] overrides = new double[size];
        Arrays.fill(overrides, Double.NaN);
        parameters.forEach((key, value) -> {
            Integer index = INDEX_BY_KEY.get(key);
            if (index != null && index < overrides.length) {
                overrides[index] = value;
            }
        });
        return overrides;
    }

    private static double[] copyAndExtend(double[] source, int length) {
        if (source.length == length) {
            return Arrays.copyOf(source, length);
        }
        double[] copy = Arrays.copyOf(source, length);
        if (length > source.length) {
            Arrays.fill(copy, source.length, length, Double.NaN);
        }
        return copy;
    }

    private static void ensureCapacity(int capacity) {
        if (DEFAULT_VALUES.length >= capacity) {
            return;
        }
        int newCapacity = Math.max(DEFAULT_VALUES.length << 1, capacity);
        DEFAULT_VALUES = Arrays.copyOf(DEFAULT_VALUES, newCapacity);
    }

    static String normalizeKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Key must not be null");
        }
        int length = key.length();
        int start = 0;
        int end = length;
        while (start < length && Character.isWhitespace(key.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(key.charAt(end - 1))) {
            end--;
        }
        if (start == end) {
            throw new IllegalArgumentException("Key must not be blank");
        }
        boolean needsLowerCase = false;
        for (int i = start; i < end; i++) {
            char c = key.charAt(i);
            if (Character.toLowerCase(c) != c) {
                needsLowerCase = true;
                break;
            }
        }
        if (start == 0 && end == length && !needsLowerCase) {
            return key;
        }
        String trimmed = (start == 0 && end == length) ? key : key.substring(start, end);
        return needsLowerCase ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }
}
