package julius.game.chessengine.tuning;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serialises {@link EngineTuning} populations back into the YAML structure that
 * {@link EngineTuningLoader} expects. This makes it possible to feed the
 * results of the genetic tuning process into subsequent runs of the engine.
 */
public final class EngineTuningWriter {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private EngineTuningWriter() {
    }

    public static void write(Path path, EngineTuningSet population) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(population, "population");
        try (OutputStream out = Files.newOutputStream(path)) {
            YAML_MAPPER.writeValue(out, toDocument(population));
        }
    }

    public static String toYaml(EngineTuningSet population) throws JsonProcessingException {
        Objects.requireNonNull(population, "population");
        return YAML_MAPPER.writeValueAsString(toDocument(population));
    }

    private static EngineTuningDocument toDocument(EngineTuningSet population) {
        List<EngineTuningConfig> configs = new ArrayList<>();
        for (EngineTuning tuning : population.population()) {
            configs.add(toConfig(tuning));
        }
        return new EngineTuningDocument(configs);
    }

    private static EngineTuningConfig toConfig(EngineTuning tuning) {
        EngineTuningConfig config = new EngineTuningConfig();
        config.name = tuning.name();
        config.ai = toAiConfig(tuning.ai());
        config.evaluation = toEvaluationConfig(tuning.evaluation());
        config.numericParameters = tuning.numericParameters().isEmpty()
                ? null
                : new LinkedHashMap<>(tuning.numericParameters());
        return config;
    }

    private static AiConfig toAiConfig(AiTuning ai) {
        AiConfig config = new AiConfig();
        config.searchThreads = ai.searchThreads();
        config.lazySmpThreads = ai.lazySmpThreads();
        config.hashSizeMb = ai.hashSizeMb();
        config.maxDepth = ai.maxDepth();
        config.timeLimitMillis = ai.timeLimitMillis();
        config.nullMovePruning = ai.nullMovePruning();
        return config;
    }

    private static EvaluationConfig toEvaluationConfig(EvaluationTuning evaluation) {
        if (evaluation.modules().isEmpty()) {
            return null;
        }
        EvaluationConfig config = new EvaluationConfig();
        config.modules = new LinkedHashMap<>();
        for (Map.Entry<String, EvaluationTuning.ModuleConfig> entry : evaluation.modules().entrySet()) {
            ModuleConfig module = new ModuleConfig();
            module.midgame = entry.getValue().midgame();
            module.endgame = entry.getValue().endgame();
            config.modules.put(entry.getKey(), module);
        }
        return config;
    }

    private static final class EngineTuningDocument {
        public List<EngineTuningConfig> population;

        private EngineTuningDocument(List<EngineTuningConfig> population) {
            this.population = population;
        }
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
        public double midgame;
        public double endgame;
    }
}

