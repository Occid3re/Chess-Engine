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
                Tuning.moveOrderingCategoryTt(),
                Tuning.moveOrderingCategoryPromotion(),
                Tuning.moveOrderingCategoryCaptureGood(),
                Tuning.moveOrderingCategoryCaptureEqual(),
                Tuning.moveOrderingCategoryKiller0(),
                Tuning.moveOrderingCategoryKiller1(),
                Tuning.moveOrderingCategoryQuiet(),
                Tuning.moveOrderingCategoryCaptureBad(),
                Tuning.moveOrderingKillerMoveScore(),
                Tuning.moveOrderingPromotionBonus(),
                Tuning.moveOrderingKiller0Bonus(),
                Tuning.moveOrderingKiller1Bonus(),
                Tuning.moveOrderingCounterMoveBonus(),
                Tuning.moveOrderingCaptureMvvMultiplier(),
                Tuning.moveOrderingCaptureSeeMultiplier(),
                Tuning.moveOrderingPromotionSeeMultiplier(),
                Tuning.moveOrderingCastlingBonus(),
                Tuning.moveOrderingCaptureSeeClamp(),
                Tuning.moveOrderingPromotionSeeClamp(),
                Tuning.moveOrderingCaptureGoodBonus(),
                Tuning.moveOrderingCaptureEqualBonus(),
                Tuning.moveOrderingCaptureBadBonus(),
                Tuning.moveOrderingCaptureLosingSeePenalty(),
                Tuning.moveOrderingQuietHistoryMultiplier(),
                Tuning.moveOrderingQuietHistoryBonus(),
                Tuning.moveOrderingMaxScore(),
                Tuning.moveOrderingHistoryScale(),
                Tuning.moveOrderingHistoryDecayDivisor()
        );
    }

    public static int killerMoveScore() {
        return Tuning.moveOrderingKillerMoveScore();
    }

    public static double historyScale() {
        return Tuning.moveOrderingHistoryScale();
    }

    public static int historyDecayDivisor() {
        return Tuning.moveOrderingHistoryDecayDivisor();
    }

    public static Map<String, Double> defaults() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        defaults.put(ParamId.MOVE_ORDERING_CATEGORY_TT.key(), ParamId.MOVE_ORDERING_CATEGORY_TT.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CATEGORY_PROMOTION.key(), ParamId.MOVE_ORDERING_CATEGORY_PROMOTION.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CATEGORY_CAPTURE_GOOD.key(), ParamId.MOVE_ORDERING_CATEGORY_CAPTURE_GOOD.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CATEGORY_CAPTURE_EQUAL.key(), ParamId.MOVE_ORDERING_CATEGORY_CAPTURE_EQUAL.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CATEGORY_KILLER0.key(), ParamId.MOVE_ORDERING_CATEGORY_KILLER0.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CATEGORY_KILLER1.key(), ParamId.MOVE_ORDERING_CATEGORY_KILLER1.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CATEGORY_QUIET.key(), ParamId.MOVE_ORDERING_CATEGORY_QUIET.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CATEGORY_CAPTURE_BAD.key(), ParamId.MOVE_ORDERING_CATEGORY_CAPTURE_BAD.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_KILLER_MOVE_SCORE.key(), ParamId.MOVE_ORDERING_KILLER_MOVE_SCORE.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_PROMOTION_BONUS.key(), ParamId.MOVE_ORDERING_PROMOTION_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_KILLER0_BONUS.key(), ParamId.MOVE_ORDERING_KILLER0_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_KILLER1_BONUS.key(), ParamId.MOVE_ORDERING_KILLER1_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_COUNTER_MOVE_BONUS.key(), ParamId.MOVE_ORDERING_COUNTER_MOVE_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_MVV_MULTIPLIER.key(), ParamId.MOVE_ORDERING_CAPTURE_MVV_MULTIPLIER.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_SEE_MULTIPLIER.key(), ParamId.MOVE_ORDERING_CAPTURE_SEE_MULTIPLIER.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_PROMOTION_SEE_MULTIPLIER.key(), ParamId.MOVE_ORDERING_PROMOTION_SEE_MULTIPLIER.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CASTLING_BONUS.key(), ParamId.MOVE_ORDERING_CASTLING_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_SEE_CLAMP.key(), ParamId.MOVE_ORDERING_CAPTURE_SEE_CLAMP.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_PROMOTION_SEE_CLAMP.key(), ParamId.MOVE_ORDERING_PROMOTION_SEE_CLAMP.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_GOOD_BONUS.key(), ParamId.MOVE_ORDERING_CAPTURE_GOOD_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_EQUAL_BONUS.key(), ParamId.MOVE_ORDERING_CAPTURE_EQUAL_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_BAD_BONUS.key(), ParamId.MOVE_ORDERING_CAPTURE_BAD_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_CAPTURE_LOSING_SEE_PENALTY.key(), ParamId.MOVE_ORDERING_CAPTURE_LOSING_SEE_PENALTY.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_QUIET_HISTORY_MULTIPLIER.key(), ParamId.MOVE_ORDERING_QUIET_HISTORY_MULTIPLIER.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_QUIET_HISTORY_BONUS.key(), ParamId.MOVE_ORDERING_QUIET_HISTORY_BONUS.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_MAX_SCORE.key(), ParamId.MOVE_ORDERING_MAX_SCORE.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_HISTORY_SCALE.key(), ParamId.MOVE_ORDERING_HISTORY_SCALE.defaultValue());
        defaults.put(ParamId.MOVE_ORDERING_HISTORY_DECAY_DIVISOR.key(), ParamId.MOVE_ORDERING_HISTORY_DECAY_DIVISOR.defaultValue());
        return Collections.unmodifiableMap(defaults);
    }

    public record Snapshot(
            int categoryTt,
            int categoryPromotion,
            int categoryCaptureGood,
            int categoryCaptureEqual,
            int categoryKiller0,
            int categoryKiller1,
            int categoryQuiet,
            int categoryCaptureBad,
            int killerMoveScore,
            int promotionBonus,
            int killer0Bonus,
            int killer1Bonus,
            int counterMoveBonus,
            int captureMvvMultiplier,
            int captureSeeMultiplier,
            int promotionSeeMultiplier,
            int castlingBonus,
            int captureSeeClamp,
            int promotionSeeClamp,
            int captureGoodBonus,
            int captureEqualBonus,
            int captureBadBonus,
            int captureLosingSeePenalty,
            double quietHistoryMultiplier,
            int quietHistoryBonus,
            int maxScore,
            double historyScale,
            int historyDecayDivisor
    ) {
        public Map<String, Number> asMap() {
            Map<String, Number> values = new LinkedHashMap<>();
            values.put("moveOrdering.category.tt", categoryTt);
            values.put("moveOrdering.category.promotion", categoryPromotion);
            values.put("moveOrdering.category.captureGood", categoryCaptureGood);
            values.put("moveOrdering.category.captureEqual", categoryCaptureEqual);
            values.put("moveOrdering.category.killer0", categoryKiller0);
            values.put("moveOrdering.category.killer1", categoryKiller1);
            values.put("moveOrdering.category.quiet", categoryQuiet);
            values.put("moveOrdering.category.captureBad", categoryCaptureBad);
            values.put("moveOrdering.killerMoveScore", killerMoveScore);
            values.put("moveOrdering.promotionBonus", promotionBonus);
            values.put("moveOrdering.killer0Bonus", killer0Bonus);
            values.put("moveOrdering.killer1Bonus", killer1Bonus);
            values.put("moveOrdering.counterMoveBonus", counterMoveBonus);
            values.put("moveOrdering.captureMvvMultiplier", captureMvvMultiplier);
            values.put("moveOrdering.captureSeeMultiplier", captureSeeMultiplier);
            values.put("moveOrdering.promotionSeeMultiplier", promotionSeeMultiplier);
            values.put("moveOrdering.castlingBonus", castlingBonus);
            values.put("moveOrdering.captureSeeClamp", captureSeeClamp);
            values.put("moveOrdering.promotionSeeClamp", promotionSeeClamp);
            values.put("moveOrdering.captureGoodBonus", captureGoodBonus);
            values.put("moveOrdering.captureEqualBonus", captureEqualBonus);
            values.put("moveOrdering.captureBadBonus", captureBadBonus);
            values.put("moveOrdering.captureLosingSeePenalty", captureLosingSeePenalty);
            values.put("moveOrdering.quietHistoryMultiplier", quietHistoryMultiplier);
            values.put("moveOrdering.quietHistoryBonus", quietHistoryBonus);
            values.put("moveOrdering.maxScore", maxScore);
            values.put("moveOrdering.historyScale", historyScale);
            values.put("moveOrdering.historyDecayDivisor", historyDecayDivisor);
            return Collections.unmodifiableMap(values);
        }
    }
}
