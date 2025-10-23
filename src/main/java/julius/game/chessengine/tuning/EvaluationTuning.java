package julius.game.chessengine.tuning;

import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.EvaluationWeights;
import julius.game.chessengine.evaluation.KingSafetyModule;
import julius.game.chessengine.evaluation.MaterialModule;
import julius.game.chessengine.evaluation.PawnStructureModule;
import julius.game.chessengine.evaluation.ThreatModule;
import julius.game.chessengine.evaluation.learning.LearningEvaluationModule;

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

    private static final Map<String, String> KNOWN_MODULE_NAMES = createKnownModuleNames();

    private final Map<String, ModuleConfig> modules;
    private final Map<String, String> displayNames;
    private final EvaluationWeights weights;

    private EvaluationTuning(Map<String, ModuleConfig> modules, Map<String, String> displayNames) {
        this.modules = modules;
        this.displayNames = displayNames;
        this.weights = buildWeights(modules);
    }

    public static EvaluationTuning identity() {
        return new EvaluationTuning(Collections.emptyMap(), Collections.emptyMap());
    }

    public static EvaluationTuning of(Map<String, ModuleConfig> modules) {
        if (modules == null || modules.isEmpty()) {
            return identity();
        }
        Map<String, ModuleConfig> normalized = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        modules.forEach((name, cfg) -> {
            if (name == null || name.isBlank() || cfg == null) {
                return;
            }
            String normalizedName = normalize(name);
            normalized.put(normalizedName, cfg);
            names.put(normalizedName, deriveDisplayName(name));
        });
        if (normalized.isEmpty()) {
            return identity();
        }
        return new EvaluationTuning(
                Collections.unmodifiableMap(normalized),
                Collections.unmodifiableMap(names)
        );
    }

    public Map<String, ModuleConfig> modules() {
        return modules;
    }

    public String displayNameFor(String moduleKey) {
        if (moduleKey == null || moduleKey.isBlank()) {
            return moduleKey;
        }
        return displayNames.getOrDefault(moduleKey, moduleKey);
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
        Map<String, String> mutatedNames = new LinkedHashMap<>();
        modules.forEach((name, cfg) -> {
            double mid = mutateWeight(cfg.midgame(), random, strength);
            double end = mutateWeight(cfg.endgame(), random, strength);
            mutated.put(name, new ModuleConfig(mid, end));
            mutatedNames.put(name, displayNameFor(name));
        });
        return new EvaluationTuning(
                Collections.unmodifiableMap(mutated),
                Collections.unmodifiableMap(mutatedNames)
        );
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

    private static String deriveDisplayName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String normalized = normalize(name);
        String known = KNOWN_MODULE_NAMES.get(normalized);
        if (known != null) {
            return known;
        }
        if (!name.equals(name.toLowerCase(Locale.ROOT))) {
            return name;
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static Map<String, String> createKnownModuleNames() {
        Map<String, String> names = new LinkedHashMap<>();
        register(names, MaterialModule.class);
        register(names, PawnStructureModule.class);
        register(names, ActivityModule.class);
        register(names, KingSafetyModule.class);
        register(names, ThreatModule.class);
        register(names, LearningEvaluationModule.class);
        return Collections.unmodifiableMap(names);
    }

    private static void register(Map<String, String> names, Class<? extends EvaluationModule> moduleClass) {
        names.put(normalize(moduleClass.getSimpleName()), moduleClass.getSimpleName());
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

