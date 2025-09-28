package julius.game.chessengine.tuning;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Serialises {@link EngineTuningSet} instances to a YAML structure that mirrors the format accepted
 * by {@link EngineTuningLoader}.
 */
public final class EngineTuningWriter {

    private static final Yaml YAML_MAPPER = new Yaml(createOptions());

    private EngineTuningWriter() {
    }

    public static void write(EngineTuningSet population, Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (population == null || population.isEmpty()) {
            throw new IllegalArgumentException("Population must contain at least one tuning");
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(path, UTF_8)) {
            YAML_MAPPER.dump(toDocument(population), writer);
        }
    }

    public static String toYaml(EngineTuningSet population) {
        if (population == null || population.isEmpty()) {
            throw new IllegalArgumentException("Population must contain at least one tuning");
        }
        try (StringWriter writer = new StringWriter()) {
            YAML_MAPPER.dump(toDocument(population), writer);
            return writer.toString();
        } catch (IOException ex) {
            // StringWriter does not throw IOException, but keep signature future-proof
            throw new IllegalStateException("Failed to render tuning definition", ex);
        }
    }

    private static DumperOptions createOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(1);
        options.setSplitLines(false);
        return options;
    }

    private static Map<String, Object> toDocument(EngineTuningSet population) {
        Map<String, Object> document = new LinkedHashMap<>();
        List<Map<String, Object>> configs = new ArrayList<>();
        for (EngineTuning tuning : population.population()) {
            configs.add(toConfig(tuning));
        }
        document.put("population", configs);
        return document;
    }

    private static Map<String, Object> toConfig(EngineTuning tuning) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (tuning.name() != null && !tuning.name().isBlank()) {
            config.put("name", tuning.name());
        }
        Map<String, Object> ai = toAiConfig(tuning.ai());
        if (!ai.isEmpty()) {
            config.put("ai", ai);
        }
        Map<String, Object> evaluation = toEvaluationConfig(tuning.evaluation());
        if (!evaluation.isEmpty()) {
            config.put("evaluation", evaluation);
        }
        if (!tuning.numericParameters().isEmpty()) {
            config.put("numericParameters", new LinkedHashMap<>(tuning.numericParameters()));
        }
        return config;
    }

    private static Map<String, Object> toAiConfig(AiTuning tuning) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("searchThreads", tuning.searchThreads());
        config.put("lazySmpThreads", tuning.lazySmpThreads());
        config.put("hashSizeMb", tuning.hashSizeMb());
        config.put("maxDepth", tuning.maxDepth());
        config.put("timeLimitMillis", tuning.timeLimitMillis());
        config.put("nullMovePruning", tuning.nullMovePruning());
        return config;
    }

    private static Map<String, Object> toEvaluationConfig(EvaluationTuning tuning) {
        Map<String, EvaluationTuning.ModuleConfig> modules = tuning.modules();
        if (modules.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> serializedModules = new LinkedHashMap<>();
        modules.forEach((name, moduleConfig) -> {
            Map<String, Object> module = new LinkedHashMap<>();
            module.put("midgame", moduleConfig.midgame());
            module.put("endgame", moduleConfig.endgame());
            serializedModules.put(name, module);
        });
        config.put("modules", serializedModules);
        return config;
    }
}

