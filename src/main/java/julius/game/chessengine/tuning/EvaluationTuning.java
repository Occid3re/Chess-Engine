package julius.game.chessengine.tuning;

import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.EvaluationWeights;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Configuration holder for module weights used by the evaluation pipeline. The configuration is
 * expressed as a mapping from module simple names to {@link ModuleConfig} instances.
 */
public final class EvaluationTuning {

    private final Map<String, ModuleConfig> modules;
    private final EvaluationWeights weights;

    private EvaluationTuning(Map<String, ModuleConfig> modules) {
        this.modules = modules;
        this.weights = buildWeights(modules);
    }

    public static EvaluationTuning identity() {
        return new EvaluationTuning(Collections.emptyMap());
    }

    public static EvaluationTuning of(Map<String, ModuleConfig> modules) {
        if (modules == null || modules.isEmpty()) {
            return identity();
        }
        Map<String, ModuleConfig> normalized = new LinkedHashMap<>();
        modules.forEach((name, cfg) -> {
            if (name == null || name.isBlank() || cfg == null) {
                return;
            }
            normalized.put(normalize(name), cfg);
        });
        if (normalized.isEmpty()) {
            return identity();
        }
        return new EvaluationTuning(Collections.unmodifiableMap(normalized));
    }

    public Map<String, ModuleConfig> modules() {
        return modules;
    }

    public EvaluationWeights toWeights() {
        return weights;
    }

    public EvaluationWeights weights() {
        return weights;
    }

    public EvaluationTuning mutate(Random random, double strength) {
        Objects.requireNonNull(random, "random");
        if (strength <= 0.0 || modules.isEmpty()) {
            return this;
        }
        Map<String, ModuleConfig> mutated = new LinkedHashMap<>();
        modules.forEach((name, cfg) -> {
            double mid = mutateWeight(cfg.midgame(), random, strength);
            double end = mutateWeight(cfg.endgame(), random, strength);
            mutated.put(name, new ModuleConfig(mid, end));
        });
        return EvaluationTuning.of(mutated);
    }

    private static EvaluationWeights buildWeights(Map<String, ModuleConfig> modules) {
        if (modules == null || modules.isEmpty()) {
            return EvaluationWeights.identity();
        }
        Map<String, EvaluationWeights.ModuleWeight> mapped = modules.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new EvaluationWeights.ModuleWeight(entry.getValue().midgame(), entry.getValue().endgame())
                ));
        return EvaluationWeights.of(mapped);
    }

    private static double mutateWeight(double value, Random random, double strength) {
        double variance = Math.max(0.05, strength);
        double factor = 1.0 + random.nextGaussian() * variance;
        double mutated = value * factor;
        if (!Double.isFinite(mutated) || mutated < -100.0) {
            mutated = -100.0;
        } else if (mutated > 100.0) {
            mutated = 100.0;
        }
        return mutated;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Convenience accessor for retrieving a configured module weight.
     */
    public EvaluationWeights.ModuleWeight resolveWeight(Class<? extends EvaluationModule> module) {
        if (module == null) {
            return new EvaluationWeights.ModuleWeight(1.0, 1.0);
        }
        ModuleConfig cfg = modules.getOrDefault(normalize(module.getSimpleName()), ModuleConfig.IDENTITY);
        return new EvaluationWeights.ModuleWeight(cfg.midgame(), cfg.endgame());
    }

    public static final class ModuleConfig {
        private static final ModuleConfig IDENTITY = new ModuleConfig(1.0, 1.0);

        private final double midgame;
        private final double endgame;

        public ModuleConfig() {
            this(1.0, 1.0);
        }

        public ModuleConfig(double midgame, double endgame) {
            if (!Double.isFinite(midgame) || !Double.isFinite(endgame)) {
                throw new IllegalArgumentException("Module weights must be finite");
            }
            this.midgame = midgame;
            this.endgame = endgame;
        }

        public double midgame() {
            return midgame;
        }

        public double endgame() {
            return endgame;
        }
    }
}

