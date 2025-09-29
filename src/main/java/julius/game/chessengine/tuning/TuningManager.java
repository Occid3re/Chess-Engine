package julius.game.chessengine.tuning;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring-managed helper that keeps the currently active tuning population in memory and reloads
 * it from disk when requested.
 */
@Component
@Log4j2
public class TuningManager {

    private final Path tuningFile;
    private static final String DEFAULT_TUNING_RESOURCE = "tuning/seed-tunings.yaml";

    private volatile EngineTuningSet population = EngineTuningSet.empty();

    public TuningManager(@Value("${chessengine.tuning.file:}") String tuningFilePath) {
        if (tuningFilePath == null || tuningFilePath.isBlank()) {
            this.tuningFile = null;
            log.info("No chessengine.tuning.file configured; loading bundled defaults from {}", DEFAULT_TUNING_RESOURCE);
            loadDefaultPopulation();
            return;
        }
        this.tuningFile = Path.of(tuningFilePath);
        if (Files.exists(this.tuningFile)) {
            try {
                this.population = EngineTuningLoader.load(this.tuningFile);
                log.info("Loaded {} tuning configurations from {}", population.population().size(), this.tuningFile);
            } catch (IOException e) {
                log.warn("Failed to load tuning file {}", this.tuningFile, e);
            }
        } else {
            log.info("Tuning file {} does not exist; starting with empty population", this.tuningFile);
        }
    }

    public synchronized EngineTuningSet reload() {
        if (tuningFile == null) {
            log.info("Reload requested without external tuning file; reloading bundled defaults from {}", DEFAULT_TUNING_RESOURCE);
            loadDefaultPopulation();
            return population;
        }
        try {
            population = EngineTuningLoader.load(tuningFile);
            log.info("Reloaded {} tuning configurations from {}", population.population().size(), tuningFile);
        } catch (IOException e) {
            log.warn("Failed to reload tuning file {}", tuningFile, e);
        }
        return population;
    }

    public EngineTuningSet currentPopulation() {
        return population;
    }

    public boolean hasPopulation() {
        return !population.isEmpty();
    }

    private void loadDefaultPopulation() {
        Resource resource = new ClassPathResource(DEFAULT_TUNING_RESOURCE);
        if (!resource.exists()) {
            log.warn("Default tuning resource {} not found on classpath", DEFAULT_TUNING_RESOURCE);
            population = EngineTuningSet.empty();
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            population = EngineTuningLoader.load(in);
            log.info("Loaded {} tuning configurations from classpath resource {}", population.population().size(), DEFAULT_TUNING_RESOURCE);
        } catch (IOException e) {
            log.warn("Failed to load default tuning resource {}", DEFAULT_TUNING_RESOURCE, e);
            population = EngineTuningSet.empty();
        }
    }
}
