package julius.game.chessengine.tuning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
        EngineTuningDocument document = YAML_MAPPER.readValue(inputStream, EngineTuningDocument.class);
        if (document == null || document.population == null || document.population.isEmpty()) {
            return EngineTuningSet.empty();
        }
        List<EngineTuning> tunings = document.population.stream()
                .map(EngineTuningLoader::toEngineTuning)
                .collect(Collectors.toUnmodifiableList());
        return new EngineTuningSet(tunings);
    }

    public static EngineTuningSet loadFromString(String yaml) throws JsonProcessingException {
        EngineTuningDocument document = YAML_MAPPER.readValue(yaml, EngineTuningDocument.class);
        if (document == null || document.population == null || document.population.isEmpty()) {
            return EngineTuningSet.empty();
        }
        List<EngineTuning> tunings = document.population.stream()
                .map(EngineTuningLoader::toEngineTuning)
                .collect(Collectors.toUnmodifiableList());
        return new EngineTuningSet(tunings);
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
        if (config == null) {
            return AiTuning.defaults();
        }
        AiTuning.Builder builder = AiTuning.builder();
        if (config.searchThreads != null) {
            builder.searchThreads(config.searchThreads);
        }
        if (config.lazySmpThreads != null) {
            builder.lazySmpThreads(config.lazySmpThreads);
        }
        if (config.hashSizeMb != null) {
            builder.hashSizeMb(config.hashSizeMb);
        }
        if (config.maxDepth != null) {
            builder.maxDepth(config.maxDepth);
        }
        if (config.timeLimitMillis != null) {
            builder.timeLimitMillis(config.timeLimitMillis);
        }
        if (config.nullMovePruning != null) {
            builder.nullMovePruning(config.nullMovePruning);
        }
        return builder.build();
    }

    private static EvaluationTuning toEvaluation(EvaluationConfig config) {
        if (config == null || config.modules == null || config.modules.isEmpty()) {
            return EvaluationTuning.identity();
        }
        Map<String, EvaluationTuning.ModuleConfig> modules = config.modules.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        entry -> new EvaluationTuning.ModuleConfig(entry.getValue().midgame, entry.getValue().endgame)
                ));
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
}
