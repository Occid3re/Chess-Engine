package julius.game.chessengine.tuning;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.introspector.BeanAccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Helper capable of loading {@link EngineTuning} definitions from a YAML document. The expected
 * structure is:
 *
 * <pre>{@code
 * population:
 *   - name: baseline
 *     # The optional "ai" section is accepted for backwards compatibility but ignored,
 *     # as engine-search parameters are no longer tuned via YAML definitions.
 *     ai:
 *       maxDepth: 32
 *       timeLimitMillis: 50
 *     evaluation:
 *       modules:
 *         MaterialModule:
 *           midgame: 1.0
 *           endgame: 1.0
 * }</pre>
 */
public final class EngineTuningLoader {

    private static final Yaml YAML_MAPPER = createYamlMapper();

    private EngineTuningLoader() {
    }

    public static EngineTuningSet load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        }
    }

    public static EngineTuningSet load(InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream");
        EngineTuningDocument document = readDocument(() -> YAML_MAPPER.load(inputStream));
        return toTuningSet(document);
    }

    public static EngineTuningSet loadFromString(String yaml) throws IOException {
        Objects.requireNonNull(yaml, "yaml");
        EngineTuningDocument document = readDocument(() -> YAML_MAPPER.load(yaml));
        return toTuningSet(document);
    }

    private static EngineTuningDocument readDocument(YamlLoader loader) throws IOException {
        try {
            Object loaded = loader.load();
            if (loaded == null) {
                return null;
            }
            if (loaded instanceof EngineTuningDocument document) {
                return document;
            }
            throw new IOException("Unexpected YAML structure for engine tuning definition");
        } catch (YAMLException ex) {
            throw new IOException("Failed to parse engine tuning definition", ex);
        }
    }

    private static EngineTuningSet toTuningSet(EngineTuningDocument document) {
        if (document == null || document.population == null || document.population.isEmpty()) {
            return EngineTuningSet.empty();
        }
        List<EngineTuning> tunings = document.population.stream()
                .map(EngineTuningLoader::toEngineTuning)
                .collect(Collectors.toUnmodifiableList());
        return new EngineTuningSet(tunings);
    }

    private static Yaml createYamlMapper() {
        LoaderOptions options = new LoaderOptions();
        Constructor constructor = new Constructor(EngineTuningDocument.class, options);
        constructor.getPropertyUtils().setBeanAccess(BeanAccess.FIELD);
        constructor.getPropertyUtils().setSkipMissingProperties(true);
        return new Yaml(constructor);
    }

    private static EngineTuning toEngineTuning(EngineTuningConfig config) {
        if (config == null) {
            return EngineTuning.builder().build();
        }
        EngineTuning.Builder builder = EngineTuning.builder();
        if (config.name != null && !config.name.isBlank()) {
            builder.name(config.name);
        }
        builder.ai(toAiTuning(config.ai));
        builder.evaluation(toEvaluation(config.evaluation));
        builder.numericParameters(normalizeNumeric(config.numericParameters));
        return builder.build();
    }

    private static AiTuning toAiTuning(AiConfig config) {
        // Search configuration is intentionally kept outside of the genetic tuning pipeline to
        // prevent accidental optimisation of engine runtime parameters. Any values present in the
        // YAML document are therefore ignored in favour of the runtime defaults.
        return AiTuning.defaults();
    }

    private static EvaluationTuning toEvaluation(EvaluationConfig config) {
        if (config == null || config.modules == null || config.modules.isEmpty()) {
            return EvaluationTuning.identity();
        }
        Map<String, EvaluationTuning.ModuleConfig> modules = new LinkedHashMap<>();
        config.modules.forEach((name, moduleConfig) -> {
            if (name == null || name.isBlank() || moduleConfig == null) {
                return;
            }
            modules.put(name, new EvaluationTuning.ModuleConfig(moduleConfig.midgame, moduleConfig.endgame));
        });
        return EvaluationTuning.of(modules);
    }

    private static Map<String, Double> normalizeNumeric(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return values.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null)
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        Map.Entry::getValue
                ));
    }

    private static final class EngineTuningDocument {
        public List<EngineTuningConfig> population;
    }

    private static final class EngineTuningConfig {
        public String name;
        public AiConfig ai;
        public EvaluationConfig evaluation;
        public Map<String, Double> numericParameters;
    }

    private static final class AiConfig {
        public Integer searchThreads;
        public Integer lazySmpThreads;
        public Integer hashSizeMb;
        public Integer maxDepth;
        public Long timeLimitMillis;
        public Boolean nullMovePruning;
    }

    private static final class EvaluationConfig {
        public Map<String, ModuleConfig> modules;
    }

    private static final class ModuleConfig {
        public double midgame = 1.0;
        public double endgame = 1.0;
    }

    @FunctionalInterface
    private interface YamlLoader {
        Object load();
    }
}
