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
 * Serialises {@link EngineTuningSet} instances to a YAML structure that mirrors the format accepted
 * by {@link EngineTuningLoader}.
 */
public final class EngineTuningWriter {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    private EngineTuningWriter() {
    }

    public static void write(EngineTuningSet population, Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (population == null || population.isEmpty()) {
            throw new IllegalArgumentException("Population must contain at least one tuning");
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            YAML_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(out, toDocument(population));
        }
    }

    public static String toYaml(EngineTuningSet population) throws JsonProcessingException {
        if (population == null || population.isEmpty()) {
            throw new IllegalArgumentException("Population must contain at least one tuning");
        }
        return YAML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toDocument(population));
    }

    private static EngineTuningDocument toDocument(EngineTuningSet population) {
        List<EngineTuningConfig> configs = new ArrayList<>();
        for (EngineTuning tuning : population.population()) {
            configs.add(toConfig(tuning));
        }
        EngineTuningDocument document = new EngineTuningDocument();
        document.population = configs;
        return document;
    }

    private static EngineTuningConfig toConfig(EngineTuning tuning) {
        EngineTuningConfig config = new EngineTuningConfig();
        config.name = tuning.name();
        config.ai = toAiConfig(tuning.ai());
        config.evaluation = toEvaluationConfig(tuning.evaluation());
        config.numericParameters = new LinkedHashMap<>(tuning.numericParameters());
        return config;
    }

    private static AiConfig toAiConfig(AiTuning tuning) {
        AiConfig config = new AiConfig();
        config.searchThreads = tuning.searchThreads();
        config.lazySmpThreads = tuning.lazySmpThreads();
        config.hashSizeMb = tuning.hashSizeMb();
        config.maxDepth = tuning.maxDepth();
        config.timeLimitMillis = tuning.timeLimitMillis();
        config.nullMovePruning = tuning.nullMovePruning();
        return config;
    }

    private static EvaluationConfig toEvaluationConfig(EvaluationTuning tuning) {
        Map<String, EvaluationTuning.ModuleConfig> modules = tuning.modules();
        if (modules.isEmpty()) {
            return null;
        }
        EvaluationConfig config = new EvaluationConfig();
        config.modules = new LinkedHashMap<>();
        modules.forEach((name, moduleConfig) -> {
            ModuleConfig module = new ModuleConfig();
            module.midgame = moduleConfig.midgame();
            module.endgame = moduleConfig.endgame();
            config.modules.put(name, module);
        });
        return config;
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
        public int searchThreads;
        public int lazySmpThreads;
        public int hashSizeMb;
        public int maxDepth;
        public long timeLimitMillis;
        public boolean nullMovePruning;
    }

    private static final class EvaluationConfig {
        public Map<String, ModuleConfig> modules;
    }

    private static final class ModuleConfig {
        public double midgame;
        public double endgame;
    }
}

