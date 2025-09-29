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

    @Test
    void reloadAppliesNumericParametersFromBundledDefaults() {
        // Start with a clearly distinct value so we can observe the reload effect.
        NumericTuningParameters.setGlobal(Map.of("moveordering.killermovescore", 1.0));

        EngineTuningBootstrap.reloadDefaults();

        double killerScore = NumericTuningParameters.resolve("moveOrdering.killerMoveScore", 0.0);
        assertThat(killerScore).isGreaterThan(10_000.0); // bundled defaults lift the baseline value
    }
}
