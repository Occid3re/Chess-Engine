package julius.game.chessengine.tuning;

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

    private static final Map<String, Double> DEFAULTS = new LinkedHashMap<>();
    private static final ThreadLocal<Map<String, Double>> THREAD_PARAMETERS = new ThreadLocal<>();
    private static volatile Map<String, Double> GLOBAL_PARAMETERS = Map.of();

    private NumericTuningParameters() {
    }

    static synchronized void registerDefault(String key, double defaultValue) {
        DEFAULTS.putIfAbsent(key, defaultValue);
    }

    static double resolve(String key, double defaultValue) {
        Objects.requireNonNull(key, "key");
        String normalized = normalizeKey(key);
        Map<String, Double> local = THREAD_PARAMETERS.get();
        if (local != null) {
            Double value = local.get(normalized);
            if (value != null) {
                return value;
            }
        }
        Double global = GLOBAL_PARAMETERS.get(normalized);
        if (global != null) {
            return global;
        }
        synchronized (DEFAULTS) {
            return DEFAULTS.getOrDefault(normalized, defaultValue);
        }
    }

    static Map<String, Double> defaults() {
        synchronized (DEFAULTS) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(DEFAULTS));
        }
    }

    public static AutoCloseable use(Map<String, Double> parameters) {
        Map<String, Double> normalized = normalize(parameters);
        Map<String, Double> previous = THREAD_PARAMETERS.get();
        THREAD_PARAMETERS.set(normalized);
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
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Key must not be blank");
        }
        return normalized;
    }
}
