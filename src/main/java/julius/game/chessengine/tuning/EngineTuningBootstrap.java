package julius.game.chessengine.tuning;

import julius.game.chessengine.utils.Score;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Bootstraps engine tuning defaults so the engine always starts with a sensible configuration,
 * regardless of whether the Spring application context is present. The bundled YAML resource is
 * loaded when no external tuning file is configured via the {@code chessengine.tuning.file}
 * system property. The first entry in the population is applied as the active tuning.
 */
@Log4j2
public final class EngineTuningBootstrap {

    public static final String DEFAULT_TUNING_RESOURCE = "tuning/seed-tunings.yaml";
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final Object LOCK = new Object();

    private static LoadedTuning cached;
    private static boolean applied;

    private EngineTuningBootstrap() {
    }

    /**
     * Ensures the default tuning has been loaded and applied. Subsequent invocations are cheap and
     * return immediately once the defaults are in place.
     */
    public static void ensureDefaultTuning() {
        synchronized (LOCK) {
            if (!applied) {
                cached = loadPopulation();
                apply(cached);
                applied = true;
            }
        }
    }

    /**
     * Reloads the configured tuning source. When no external file is configured this falls back to
     * the bundled defaults. The loaded population is returned so callers (e.g. the {@link TuningManager})
     * can inspect the available configurations.
     */
    public static LoadedTuning reloadDefaults() {
        synchronized (LOCK) {
            cached = loadPopulation();
            apply(cached);
            applied = true;
            return cached;
        }
    }

    static LoadedTuning loadBundledDefaults() {
        return loadFromResource();
    }

    private static LoadedTuning loadPopulation() {
        String configured = System.getProperty("chessengine.tuning.file");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (Files.isRegularFile(path)) {
                try {
                    EngineTuningSet set = EngineTuningLoader.load(path);
                    log.info("Loaded {} tuning configurations from {}", set.population().size(), path.toAbsolutePath());
                    return new LoadedTuning(set, path.toAbsolutePath().toString());
                } catch (IOException e) {
                    log.warn("Failed to load tuning file {}; falling back to bundled defaults.", path.toAbsolutePath(), e);
                }
            } else {
                log.warn("Configured tuning file {} does not exist; falling back to bundled defaults.", path.toAbsolutePath());
            }
        }
        return loadFromResource();
    }

    private static LoadedTuning loadFromResource() {
        String source = CLASSPATH_PREFIX + DEFAULT_TUNING_RESOURCE;
        try (InputStream in = EngineTuningBootstrap.class.getClassLoader()
                .getResourceAsStream(DEFAULT_TUNING_RESOURCE)) {
            if (in == null) {
                log.warn("Bundled tuning resource {} not found on the classpath.", source);
                return new LoadedTuning(EngineTuningSet.empty(), source);
            }
            EngineTuningSet set = EngineTuningLoader.load(in);
            log.info("Loaded {} tuning configurations from {}", set.population().size(), source);
            return new LoadedTuning(set, source);
        } catch (IOException e) {
            log.warn("Failed to load bundled tuning resource {}", source, e);
            return new LoadedTuning(EngineTuningSet.empty(), source);
        }
    }

    private static void apply(LoadedTuning loaded) {
        if (loaded.population().isEmpty()) {
            NumericTuningParameters.setGlobal(Map.of());
            Score.setGlobalFactory(Score.forEvaluationWeights(null));
            log.info("No engine tuning configurations available after loading from {}; using built-in defaults.",
                    loaded.sourceDescription());
            return;
        }
        EngineTuning tuning = loaded.primary();
        NumericTuningParameters.setGlobal(tuning.numericParameters());
        Score.setGlobalFactory(Score.forEvaluationWeights(tuning.evaluationWeights()));
        log.info("Applied engine tuning \"{}\" from {} ({} configurations available).",
                tuning.name() != null && !tuning.name().isBlank() ? tuning.name() : "default",
                loaded.sourceDescription(),
                loaded.population().population().size());
    }

    static final class LoadedTuning {
        private final EngineTuningSet population;
        private final EngineTuning primary;
        private final String sourceDescription;

        LoadedTuning(EngineTuningSet population, String sourceDescription) {
            this.population = population != null ? population : EngineTuningSet.empty();
            this.primary = this.population.primary();
            this.sourceDescription = sourceDescription != null ? sourceDescription : "";
        }

        EngineTuningSet population() {
            return population;
        }

        EngineTuning primary() {
            return primary;
        }

        String sourceDescription() {
            return sourceDescription;
        }
    }
}

