package julius.game.chessengine.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight container for numeric evaluation parameters. Instances are immutable and normalise
 * parameter names to lower-case so lookups become case-insensitive. Values are stored as doubles to
 * accommodate both integer and floating point tunables while still allowing modules to coerce them
 * to the desired primitive type.
 */
public final class EvaluationParameters {

    private static final EvaluationParameters IDENTITY = new EvaluationParameters(Collections.emptyMap());

    private final Map<String, Double> parameters;

    private EvaluationParameters(Map<String, Double> parameters) {
        this.parameters = parameters;
    }

    public static EvaluationParameters identity() {
        return IDENTITY;
    }

    public static EvaluationParameters of(Map<String, Double> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return identity();
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || !Double.isFinite(value)) {
                return;
            }
            normalized.put(key.toLowerCase(Locale.ROOT), value);
        });
        if (normalized.isEmpty()) {
            return identity();
        }
        return new EvaluationParameters(Collections.unmodifiableMap(normalized));
    }

    public Map<String, Double> parameters() {
        return parameters;
    }

    public boolean isEmpty() {
        return parameters.isEmpty();
    }

    public int getInt(String key, int defaultValue) {
        Objects.requireNonNull(key, "key");
        Double value = parameters.get(key.toLowerCase(Locale.ROOT));
        if (value == null) {
            return defaultValue;
        }
        double coerced = Math.round(value);
        if (!Double.isFinite(coerced)) {
            return defaultValue;
        }
        if (coerced > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (coerced < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) coerced;
    }

    public long getLong(String key, long defaultValue) {
        Objects.requireNonNull(key, "key");
        Double value = parameters.get(key.toLowerCase(Locale.ROOT));
        if (value == null) {
            return defaultValue;
        }
        double coerced = Math.round(value);
        if (!Double.isFinite(coerced)) {
            return defaultValue;
        }
        if (coerced > Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (coerced < Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        return (long) coerced;
    }

    public double getDouble(String key, double defaultValue) {
        Objects.requireNonNull(key, "key");
        Double value = parameters.get(key.toLowerCase(Locale.ROOT));
        if (value == null || !Double.isFinite(value)) {
            return defaultValue;
        }
        return value;
    }
}
