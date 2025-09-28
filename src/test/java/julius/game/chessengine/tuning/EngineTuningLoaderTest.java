package julius.game.chessengine.tuning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineTuningLoaderTest {

    @Test
    void loadsPopulationFromYaml() throws Exception {
        String yaml = """
                population:
                  - name: test
                    ai:
                      searchThreads: 1
                      lazySmpThreads: 1
                      hashSizeMb: 32
                      maxDepth: 24
                      timeLimitMillis: 60
                      nullMovePruning: false
                    evaluation:
                      modules:
                        MaterialModule:
                          midgame: 1.1
                          endgame: 0.9
                """;

        EngineTuningSet set = EngineTuningLoader.loadFromString(yaml);
        assertThat(set.population()).hasSize(1);
        EngineTuning tuning = set.population().getFirst();
        assertThat(tuning.name()).isEqualTo("test");
        AiTuning defaults = AiTuning.defaults();
        assertThat(tuning.ai().maxDepth()).isEqualTo(defaults.maxDepth());
        assertThat(tuning.ai().nullMovePruning()).isEqualTo(defaults.nullMovePruning());
        assertThat(tuning.evaluation().modules())
                .containsKey("materialmodule");
    }
}
