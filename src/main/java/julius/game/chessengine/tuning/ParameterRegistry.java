package julius.game.chessengine.tuning;

import java.util.Map;
import java.util.Objects;

public final class ParameterRegistry {

    private static final ThreadLocal<ParameterSnapshot> THREAD_OVERRIDE = new ThreadLocal<>();
    private static volatile ParameterSnapshot GLOBAL_SNAPSHOT = ParameterSnapshot.defaultSnapshot();

    private ParameterRegistry() {
    }

    static ParameterSnapshot globalSnapshot() {
        return GLOBAL_SNAPSHOT;
    }

    static ParameterSnapshot threadSnapshot() {
        return THREAD_OVERRIDE.get();
    }

    static void setThreadSnapshot(ParameterSnapshot snapshot) {
        if (snapshot == null) {
            THREAD_OVERRIDE.remove();
        } else {
            THREAD_OVERRIDE.set(snapshot);
        }
    }

    static void clearThreadSnapshot() {
        THREAD_OVERRIDE.remove();
    }

    static void setGlobalSnapshot(ParameterSnapshot snapshot) {
        GLOBAL_SNAPSHOT = Objects.requireNonNull(snapshot, "snapshot");
    }

    static Double lookupExtra(String normalizedKey) {
        ParameterSnapshot override = THREAD_OVERRIDE.get();
        if (override != null) {
            Double value = override.extraValue(normalizedKey);
            if (value != null) {
                return value;
            }
        }
        return GLOBAL_SNAPSHOT.extraValue(normalizedKey);
    }

    public static double get(ParamId id) {
        Objects.requireNonNull(id, "id");
        ParameterSnapshot snapshot = THREAD_OVERRIDE.get();
        if (snapshot == null) {
            snapshot = GLOBAL_SNAPSHOT;
        }
        double value = snapshot.value(id);
        return Double.isFinite(value) ? value : id.defaultValue();
    }

    public static int getInt(ParamId id) {
        return (int) Math.round(get(id));
    }

    public static void hotReload(Map<String, Double> parameters) {
        NumericTuningParameters.hotReload(parameters);
    }
}
