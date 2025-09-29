package julius.game.chessengine.tuning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineTuningBootstrapTest {

    @BeforeEach
    void clearOverrideProperty() {
        System.clearProperty("chessengine.tuning.file");
    }

    @Test
    void loadsBundledDefaultsWhenNoExternalFileIsConfigured() {
        EngineTuningBootstrap.LoadedTuning loaded = EngineTuningBootstrap.loadBundledDefaults();

        assertThat(loaded.population()).isNotNull();
        assertThat(loaded.population().isEmpty()).isFalse();
        assertThat(loaded.sourceDescription()).isEqualTo("classpath:" + EngineTuningBootstrap.DEFAULT_TUNING_RESOURCE);
    }
}
