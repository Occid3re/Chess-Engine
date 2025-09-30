package julius.game.chessengine.tuning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Registry for move-ordering heuristics used by the search. The parameters are exposed as
 * tuning parameters so the genetic tuner can mutate them alongside the evaluation weights.
 * Consumers capture a {@link Snapshot} at construction time to obtain a stable view of the
 * configuration for the duration of a search.
 */
public final class MoveOrderingParameters {

    private MoveOrderingParameters() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                Tuning.moveOrderingKillerMoveScore(),
                Tuning.moveOrderingPromotionBonus(),
                Tuning.moveOrderingKiller0Bonus(),
                Tuning.moveOrderingKiller1Bonus(),
                Tuning.moveOrderingCounterMoveBonus(),
                Tuning.moveOrderingCaptureMvvMultiplier(),
                Tuning.moveOrderingCaptureSeeMultiplier(),
                Tuning.moveOrderingPromotionSeeMultiplier()
        );
    }

    public static int killerMoveScore() {
        return Tuning.moveOrderingKillerMoveScore();
    }

    public static Map<String, Double> defaults() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        defaults.put(ParamId.MOVE_ORDERING_KILLER_MOVE_SCORE.key(), ParamId.MOVE_ORDERING_KILLER_MOVE_SCORE.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_PROMOTION_BONUS.key(), ParamId.MOVE_ORDERING_PROMOTION_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_KILLER0_BONUS.key(), ParamId.MOVE_ORDERING_KILLER0_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_KILLER1_BONUS.key(), ParamId.MOVE_ORDERING_KILLER1_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_COUNTER_MOVE_BONUS.key(), ParamId.MOVE_ORDERING_COUNTER_MOVE_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_MVV_MULTIPLIER.key(), ParamId.MOVE_ORDERING_CAPTURE_MVV_MULTIPLIER.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_SEE_MULTIPLIER.key(), ParamId.MOVE_ORDERING_CAPTURE_SEE_MULTIPLIER.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_PROMOTION_SEE_MULTIPLIER.key(), ParamId.MOVE_ORDERING_PROMOTION_SEE_MULTIPLIER.defaultValue());
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
