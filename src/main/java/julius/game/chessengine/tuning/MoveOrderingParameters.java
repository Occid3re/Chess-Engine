package julius.game.chessengine.tuning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry for move-ordering heuristics used by the search. The parameters are exposed as
 * {@link TunableParameter}s so the genetic tuner can mutate them alongside the evaluation
 * weights. Consumers capture a {@link Snapshot} at construction time to obtain a stable view of
 * the configuration for the duration of a search.
 */
public final class MoveOrderingParameters {

    private static final TunableParameter KILLER_MOVE_SCORE =
            TunableParameter.of("moveOrdering.killerMoveScore", 10_000);
    private static final TunableParameter PROMOTION_BONUS =
            TunableParameter.of("moveOrdering.promotionBonus", 900);
    private static final TunableParameter KILLER0_BONUS =
            TunableParameter.of("moveOrdering.killer0Bonus", 50);
    private static final TunableParameter KILLER1_BONUS =
            TunableParameter.of("moveOrdering.killer1Bonus", 30);
    private static final TunableParameter COUNTER_MOVE_BONUS =
            TunableParameter.of("moveOrdering.counterMoveBonus", 400);
    private static final TunableParameter CAPTURE_MVV_MULTIPLIER =
            TunableParameter.of("moveOrdering.captureMvvMultiplier", 16);
    private static final TunableParameter CAPTURE_SEE_MULTIPLIER =
            TunableParameter.of("moveOrdering.captureSeeMultiplier", 32);
    private static final TunableParameter PROMOTION_SEE_MULTIPLIER =
            TunableParameter.of("moveOrdering.promotionSeeMultiplier", 16);

    private MoveOrderingParameters() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                KILLER_MOVE_SCORE.getInt(),
                PROMOTION_BONUS.getInt(),
                KILLER0_BONUS.getInt(),
                KILLER1_BONUS.getInt(),
                COUNTER_MOVE_BONUS.getInt(),
                CAPTURE_MVV_MULTIPLIER.getInt(),
                CAPTURE_SEE_MULTIPLIER.getInt(),
                PROMOTION_SEE_MULTIPLIER.getInt()
        );
    }

    public static int killerMoveScore() {
        return KILLER_MOVE_SCORE.getInt();
    }

    public static Map<String, Double> defaults() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        defaults.put(KILLER_MOVE_SCORE.key(), KILLER_MOVE_SCORE.defaultValue());
        defaults.put(PROMOTION_BONUS.key(), PROMOTION_BONUS.defaultValue());
        defaults.put(KILLER0_BONUS.key(), KILLER0_BONUS.defaultValue());
        defaults.put(KILLER1_BONUS.key(), KILLER1_BONUS.defaultValue());
        defaults.put(COUNTER_MOVE_BONUS.key(), COUNTER_MOVE_BONUS.defaultValue());
        defaults.put(CAPTURE_MVV_MULTIPLIER.key(), CAPTURE_MVV_MULTIPLIER.defaultValue());
        defaults.put(CAPTURE_SEE_MULTIPLIER.key(), CAPTURE_SEE_MULTIPLIER.defaultValue());
        defaults.put(PROMOTION_SEE_MULTIPLIER.key(), PROMOTION_SEE_MULTIPLIER.defaultValue());
        return Collections.unmodifiableMap(defaults);
    }

    public record Snapshot(
            int killerMoveScore,
            int promotionBonus,
            int killer0Bonus,
            int killer1Bonus,
            int counterMoveBonus,
            int captureMvvMultiplier,
            int captureSeeMultiplier,
            int promotionSeeMultiplier
    ) {
        public Map<String, Integer> asMap() {
            Map<String, Integer> values = new LinkedHashMap<>();
            values.put("moveOrdering.killerMoveScore", killerMoveScore);
            values.put("moveOrdering.promotionBonus", promotionBonus);
            values.put("moveOrdering.killer0Bonus", killer0Bonus);
            values.put("moveOrdering.killer1Bonus", killer1Bonus);
            values.put("moveOrdering.counterMoveBonus", counterMoveBonus);
            values.put("moveOrdering.captureMvvMultiplier", captureMvvMultiplier);
            values.put("moveOrdering.captureSeeMultiplier", captureSeeMultiplier);
            values.put("moveOrdering.promotionSeeMultiplier", promotionSeeMultiplier);
            return Collections.unmodifiableMap(values);
        }
    }
}
