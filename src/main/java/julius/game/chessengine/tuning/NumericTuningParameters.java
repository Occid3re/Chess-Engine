package julius.game.chessengine.tuning;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Central registry for numeric tuning parameters. Supports thread-local overrides so that
 * independent searches can evaluate different parameter sets concurrently.
 */
public final class NumericTuningParameters {

    private static final double UNSET = Double.NaN;

    private static final Map<String, Double> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, Integer> KEY_INDICES = new LinkedHashMap<>();

    private static volatile Map<String, Integer> KEY_INDEX_CACHE = Map.of();
    private static volatile Map<String, Double> GLOBAL_PARAMETERS = Map.of();
    private static volatile Map<String, Double> DEFAULT_CACHE = Map.of();
    private static volatile double[] DEFAULT_VALUES = new double[0];
    private static volatile OverrideValues GLOBAL_OVERRIDE = OverrideValues.EMPTY;

    private static final ThreadLocal<OverrideValues> THREAD_OVERRIDES = new ThreadLocal<>();

    private NumericTuningParameters() {
    }

    static synchronized int registerDefault(String key, double defaultValue) {
        Integer existing = KEY_INDICES.get(key);
        if (existing != null) {
            return existing;
        }
        int index = KEY_INDICES.size();
        KEY_INDICES.put(key, index);
        KEY_INDEX_CACHE = Collections.unmodifiableMap(new LinkedHashMap<>(KEY_INDICES));
        DEFAULTS.put(key, defaultValue);
        DEFAULT_CACHE = Collections.unmodifiableMap(new LinkedHashMap<>(DEFAULTS));
        DEFAULT_VALUES = ensureCapacity(DEFAULT_VALUES, index + 1, UNSET);
        DEFAULT_VALUES[index] = defaultValue;
        GLOBAL_OVERRIDE = GLOBAL_OVERRIDE.expandTo(index + 1);
        return index;
    }

    static double resolve(int index, double defaultValue) {
        double override = valueAt(THREAD_OVERRIDES.get(), index);
        if (!Double.isNaN(override)) {
            return override;
        }
        override = valueAt(GLOBAL_OVERRIDE, index);
        if (!Double.isNaN(override)) {
            return override;
        }
        double[] defaults = DEFAULT_VALUES;
        if (index < defaults.length) {
            double value = defaults[index];
            if (!Double.isNaN(value)) {
                return value;
            }
        }
        return defaultValue;
    }

    static double resolve(String key, double defaultValue) {
        Objects.requireNonNull(key, "key");
        String normalized = normalizeKey(key);
        Map<String, Integer> indices = KEY_INDEX_CACHE;
        Double extra = lookupExtra(THREAD_OVERRIDES.get(), normalized);
        if (extra != null) {
            return extra;
        }
        extra = lookupExtra(GLOBAL_OVERRIDE, normalized);
        if (extra != null) {
            return extra;
        }
        Integer index = indices.get(normalized);
        if (index != null) {
            return resolve(index, defaultValue);
        }
        Double fallback = DEFAULT_CACHE.get(normalized);
        return fallback != null ? fallback : defaultValue;
    }

    static Map<String, Double> defaults() {
        return DEFAULT_CACHE;
    }

    public static AutoCloseable use(Map<String, Double> parameters) {
        Map<String, Double> normalized = normalize(parameters);
        OverrideValues overrides = createOverrides(normalized);
        OverrideValues previous = THREAD_OVERRIDES.get();
        if (overrides == OverrideValues.EMPTY) {
            THREAD_OVERRIDES.remove();
        } else {
            THREAD_OVERRIDES.set(overrides);
        }
        return () -> {
            if (previous == null || previous == OverrideValues.EMPTY) {
                THREAD_OVERRIDES.remove();
            } else {
                THREAD_OVERRIDES.set(previous);
            }
        };
    }

    public static void setGlobal(Map<String, Double> parameters) {
        Map<String, Double> normalized = normalize(parameters);
        GLOBAL_PARAMETERS = normalized;
        OverrideValues overrides = createOverrides(normalized);
        GLOBAL_OVERRIDE = overrides == OverrideValues.EMPTY ? OverrideValues.EMPTY : overrides;
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

    private static OverrideValues createOverrides(Map<String, Double> normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return OverrideValues.EMPTY;
        }
        Map<String, Integer> indices = KEY_INDEX_CACHE;
        double[] overrides = null;
        int allocatedLength = 0;
        Map<String, Double> extras = null;
        for (Map.Entry<String, Double> entry : normalized.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();
            if (value == null || value.isNaN()) {
                continue;
            }
            Integer index = indices.get(key);
            if (index != null) {
                if (overrides == null) {
                    allocatedLength = DEFAULT_VALUES.length;
                    overrides = new double[Math.max(allocatedLength, index + 1)];
                    Arrays.fill(overrides, UNSET);
                    allocatedLength = overrides.length;
                } else if (index >= allocatedLength) {
                    int previousLength = allocatedLength;
                    allocatedLength = Math.max(DEFAULT_VALUES.length, index + 1);
                    overrides = Arrays.copyOf(overrides, allocatedLength);
                    Arrays.fill(overrides, previousLength, allocatedLength, UNSET);
                }
                overrides[index] = value;
            } else {
                if (extras == null) {
                    extras = new LinkedHashMap<>();
                }
                extras.put(key, value);
            }
        }
        if (overrides != null) {
            overrides = trimTrailingUnset(overrides);
        }
        Map<String, Double> extraValues = (extras == null)
                ? Map.of()
                : Collections.unmodifiableMap(extras);
        if (overrides == null && extraValues.isEmpty()) {
            return OverrideValues.EMPTY;
        }
        return new OverrideValues(overrides, extraValues);
    }

    private static double valueAt(OverrideValues overrides, int index) {
        if (overrides == null) {
            return Double.NaN;
        }
        double[] values = overrides.values;
        if (values != null && index < values.length) {
            return values[index];
        }
        return Double.NaN;
    }

    private static Double lookupExtra(OverrideValues overrides, String key) {
        if (overrides == null) {
            return null;
        }
        Map<String, Double> extras = overrides.extras;
        if (extras == null || extras.isEmpty()) {
            return null;
        }
        return extras.get(key);
    }

    private static double[] ensureCapacity(double[] values, int size, double fillValue) {
        if (values.length >= size) {
            return values;
        }
        double[] expanded = Arrays.copyOf(values, size);
        Arrays.fill(expanded, values.length, size, fillValue);
        return expanded;
    }

    private static double[] trimTrailingUnset(double[] values) {
        int last = values.length - 1;
        while (last >= 0 && Double.isNaN(values[last])) {
            last--;
        }
        if (last < 0) {
            return null;
        }
        if (last == values.length - 1) {
            return values;
        }
        return Arrays.copyOf(values, last + 1);
    }

    private static final class OverrideValues {
        static final OverrideValues EMPTY = new OverrideValues(null, Map.of());

        final double[] values;
        final Map<String, Double> extras;

        OverrideValues(double[] values, Map<String, Double> extras) {
            this.values = values;
            this.extras = extras;
        }

        OverrideValues expandTo(int size) {
            double[] current = values;
            if (current == null || current.length >= size) {
                return this;
            }
            double[] expanded = Arrays.copyOf(current, size);
            Arrays.fill(expanded, current.length, size, UNSET);
            return new OverrideValues(expanded, extras);
        }
    }
}
