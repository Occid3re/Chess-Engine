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
    private static final Map<String, Integer> KEY_TO_ID = new ConcurrentHashMap<>();
    private static final ThreadLocal<double[]> THREAD_PARAMETERS = new ThreadLocal<>();
    private static volatile double[] DEFAULT_VALUES = new double[0];
    private static volatile double[] GLOBAL_VALUES = new double[0];
    private static volatile Map<String, Double> DEFAULT_CACHE = Map.of();

    private NumericTuningParameters() {
    }

    static synchronized int registerDefault(String key, double defaultValue) {
        Integer existingId = KEY_TO_ID.get(key);
        if (existingId != null) {
            DEFAULTS.put(key, defaultValue);
            DEFAULT_CACHE = Collections.unmodifiableMap(new LinkedHashMap<>(DEFAULTS));
            if (existingId < DEFAULT_VALUES.length) {
                DEFAULT_VALUES[existingId] = defaultValue;
            }
            return existingId;
        }
        int id = KEY_TO_ID.size();
        KEY_TO_ID.put(key, id);
        DEFAULTS.put(key, defaultValue);
        DEFAULT_CACHE = Collections.unmodifiableMap(new LinkedHashMap<>(DEFAULTS));
        DEFAULT_VALUES = Arrays.copyOf(DEFAULT_VALUES, id + 1);
        DEFAULT_VALUES[id] = defaultValue;
        GLOBAL_VALUES = growWithNaN(GLOBAL_VALUES, id + 1);
        return id;
    }

    static double resolve(String key, double defaultValue) {
        Objects.requireNonNull(key, "key");
        String normalized = normalizeKey(key);
        Integer id = KEY_TO_ID.get(normalized);
        if (id == null) {
            Double fallback = DEFAULT_CACHE.get(normalized);
            return fallback != null ? fallback : defaultValue;
        }
        return resolve(id, defaultValue);
    }

    static double resolve(int id, double defaultValue) {
        double value = valueAt(THREAD_PARAMETERS.get(), id);
        if (!Double.isNaN(value)) {
            return value;
        }
        value = valueAt(GLOBAL_VALUES, id);
        if (!Double.isNaN(value)) {
            return value;
        }
        value = valueAt(DEFAULT_VALUES, id);
        return Double.isNaN(value) ? defaultValue : value;
    }

    static Map<String, Double> defaults() {
        return DEFAULT_CACHE;
    }

    public static AutoCloseable use(Map<String, Double> parameters) {
        Map<String, Double> normalized = normalize(parameters);
        double[] overrides = toValues(normalized);
        double[] previous = THREAD_PARAMETERS.get();
        if (overrides.length == 0) {
            THREAD_PARAMETERS.remove();
        } else {
            THREAD_PARAMETERS.set(overrides);
        }
        return () -> {
            if (previous == null) {
                THREAD_PARAMETERS.remove();
            } else {
                THREAD_PARAMETERS.set(previous);
            }
        };
    }

    public static void setGlobal(Map<String, Double> parameters) {
        Map<String, Double> normalized = normalize(parameters);
        GLOBAL_VALUES = toValues(normalized);
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

    private static double[] toValues(Map<String, Double> normalized) {
        if (normalized.isEmpty()) {
            return new double[0];
        }
        double[] values = new double[DEFAULT_VALUES.length];
        Arrays.fill(values, Double.NaN);
        boolean hasOverride = false;
        for (Map.Entry<String, Double> entry : normalized.entrySet()) {
            Integer id = KEY_TO_ID.get(entry.getKey());
            if (id == null || id >= values.length) {
                continue;
            }
            Double override = entry.getValue();
            if (override != null && !override.isNaN()) {
                values[id] = override;
                hasOverride = true;
            }
        }
        return hasOverride ? values : new double[0];
    }

    private static double[] growWithNaN(double[] source, int newSize) {
        if (source.length >= newSize) {
            return source;
        }
        double[] resized = Arrays.copyOf(source, newSize);
        Arrays.fill(resized, source.length, newSize, Double.NaN);
        return resized;
    }

    private static double valueAt(double[] values, int id) {
        if (values == null || id >= values.length) {
            return Double.NaN;
        }
        return values[id];
    }
}
