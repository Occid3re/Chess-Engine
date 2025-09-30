package julius.game.chessengine.tuning;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ParameterSnapshot {

    private static final ParamId[] IDS = ParamId.values();
    private static final ParameterSnapshot DEFAULT = createDefault();

    private final double[] values;
    private final Map<String, Double> extras;

    private ParameterSnapshot(double[] values, Map<String, Double> extras) {
        this.values = Objects.requireNonNull(values, "values");
        this.extras = Objects.requireNonNull(extras, "extras");
    }

    static ParameterSnapshot defaultSnapshot() {
        return DEFAULT;
    }

    private static ParameterSnapshot createDefault() {
        double[] values = new double[IDS.length];
        for (ParamId id : IDS) {
            values[id.ordinal()] = id.defaultValue();
        }
        return new ParameterSnapshot(values, Map.of());
    }

    double value(ParamId id) {
        return values[id.ordinal()];
    }

    Double extraValue(String normalizedKey) {
        return extras.get(normalizedKey);
    }

    Map<String, Double> asMap() {
        Map<String, Double> all = new LinkedHashMap<>(values.length + extras.size());
        for (ParamId id : IDS) {
            all.put(id.key(), values[id.ordinal()]);
        }
        if (!extras.isEmpty()) {
            all.putAll(extras);
        }
        return Collections.unmodifiableMap(all);
    }

    Map<String, Double> extras() {
        return extras;
    }

    static ParameterSnapshot apply(ParameterSnapshot base, Map<String, Double> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return base;
        }
        double[] baseValues = base.values;
        double[] updatedValues = null;
        Map<String, Double> baseExtras = base.extras;
        Map<String, Double> updatedExtras = null;
        boolean changed = false;

        for (Map.Entry<String, Double> entry : overrides.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();
            if (value == null || value.isNaN()) {
                continue;
            }
            ParamId id = ParamId.forKey(key);
            if (id != null) {
                double current = baseValues[id.ordinal()];
                if (Double.doubleToLongBits(current) == Double.doubleToLongBits(value)) {
                    continue;
                }
                if (updatedValues == null) {
                    updatedValues = Arrays.copyOf(baseValues, baseValues.length);
                }
                updatedValues[id.ordinal()] = value;
                changed = true;
            } else {
                Double existing = baseExtras.get(key);
                if (existing != null && Double.doubleToLongBits(existing) == Double.doubleToLongBits(value)) {
                    continue;
                }
                if (updatedExtras == null) {
                    updatedExtras = baseExtras.isEmpty()
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(baseExtras);
                }
                updatedExtras.put(key, value);
                changed = true;
            }
        }

        if (!changed) {
            return base;
        }

        double[] finalValues = (updatedValues != null) ? updatedValues : baseValues;
        Map<String, Double> finalExtras;
        if (updatedExtras != null) {
            finalExtras = updatedExtras.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(updatedExtras);
        } else {
            finalExtras = baseExtras;
        }

        if (finalValues == baseValues && finalExtras == baseExtras) {
            return base;
        }

        return new ParameterSnapshot(finalValues, finalExtras);
    }
}
