package julius.game.chessengine.tuning;

/**
 * Represents a single numeric evaluation parameter that can be tuned. Instances register their
 * default value with {@link NumericTuningParameters} so that tuning seeds can start from the
 * current handcrafted values.
 */
public final class TunableParameter {

    private final String key;
    private final double defaultValue;
    private final Double minValue;
    private final Double maxValue;

    private TunableParameter(String key, double defaultValue, Double minValue, Double maxValue) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Parameter key must not be blank");
        }
        this.key = NumericTuningParameters.normalizeKey(key);
        this.defaultValue = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        NumericTuningParameters.registerDefault(this.key, defaultValue);
    }

    public static TunableParameter of(String key, double defaultValue) {
        return new TunableParameter(key, defaultValue, null, null);
    }

    public static TunableParameter of(String key, double defaultValue, double minValue, double maxValue) {
        if (minValue > maxValue) {
            throw new IllegalArgumentException("Min value cannot exceed max value");
        }
        return new TunableParameter(key, defaultValue, minValue, maxValue);
    }

    public double get() {
        double value = NumericTuningParameters.resolve(key, defaultValue);
        if (!Double.isFinite(value)) {
            value = defaultValue;
        }
        if (minValue != null && value < minValue) {
            value = minValue;
        }
        if (maxValue != null && value > maxValue) {
            value = maxValue;
        }
        return value;
    }

    public int getInt() {
        return (int) Math.round(get());
    }

    public long getLong() {
        return Math.round(get());
    }

    public String key() {
        return key;
    }

    public double defaultValue() {
        return defaultValue;
    }
}
