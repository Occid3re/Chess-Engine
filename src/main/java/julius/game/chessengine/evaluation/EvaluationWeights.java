package julius.game.chessengine.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable container for scaling factors applied to each evaluation module. The weights are
 * represented as simple double multipliers for the midgame and endgame contributions produced by
 * a module. When no explicit weight is configured a neutral weight of {@code 1.0} is used.
 */
public final class EvaluationWeights {

    private static final ModuleWeight NEUTRAL = new ModuleWeight(1.0, 1.0);

    private final Map<String, ModuleWeight> weights;

    private EvaluationWeights(Map<String, ModuleWeight> weights) {
        this.weights = weights;
    }

    /**
     * Returns an {@link EvaluationWeights} instance that does not alter module contributions.
     */
    public static EvaluationWeights identity() {
        return new EvaluationWeights(Collections.emptyMap());
    }

    /**
     * Creates an {@link EvaluationWeights} instance using the provided mapping. The map keys are
     * compared case-insensitively against the simple class name of an evaluation module.
     */
    public static EvaluationWeights of(Map<String, ModuleWeight> rawWeights) {
        if (rawWeights == null || rawWeights.isEmpty()) {
            return identity();
        }
        Map<String, ModuleWeight> normalized = new LinkedHashMap<>();
        rawWeights.forEach((key, weight) -> {
            if (key == null || key.isBlank() || weight == null) {
                return;
            }
            normalized.put(normalizeKey(key), weight);
        });
        if (normalized.isEmpty()) {
            return identity();
        }
        return new EvaluationWeights(Collections.unmodifiableMap(normalized));
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the weight to use for the provided module. When the module has not been configured
     * explicitly this method returns the neutral weight ({@code 1.0} for both phases).
     */
    public ModuleWeight weightFor(Class<? extends EvaluationModule> moduleClass) {
        Objects.requireNonNull(moduleClass, "moduleClass");
        if (weights.isEmpty()) {
            return NEUTRAL;
        }
        ModuleWeight configured = weights.get(normalizeKey(moduleClass.getSimpleName()));
        return configured != null ? configured : NEUTRAL;
    }

    /**
     * Provides the underlying mapping for serialization or inspection purposes. The returned map
     * is immutable.
     */
    public Map<String, ModuleWeight> asMap() {
        return weights;
    }

    /**
     * Simple value object describing the scaling factors for the midgame and endgame contribution
     * of a module.
     */
    public record ModuleWeight(double midgame, double endgame) {
        public ModuleWeight {
            if (!Double.isFinite(midgame) || !Double.isFinite(endgame)) {
                throw new IllegalArgumentException("Module weights must be finite values");
            }
        }
    }
}
