package julius.game.chessengine.tuning;

import java.util.Collections;
import java.util.List;

/**
 * Immutable wrapper around a population of {@link EngineTuning} instances. The list is guaranteed
 * to be unmodifiable.
 */
public final class EngineTuningSet {

    private static final EngineTuningSet EMPTY = new EngineTuningSet(Collections.emptyList());

    private final List<EngineTuning> population;

    public EngineTuningSet(List<EngineTuning> population) {
        this.population = population == null
                ? Collections.emptyList()
                : List.copyOf(population);
    }

    public static EngineTuningSet empty() {
        return EMPTY;
    }

    public List<EngineTuning> population() {
        return population;
    }

    public EngineTuning primary() {
        return population.isEmpty() ? null : population.get(0);
    }

    public boolean isEmpty() {
        return population.isEmpty();
    }
}

