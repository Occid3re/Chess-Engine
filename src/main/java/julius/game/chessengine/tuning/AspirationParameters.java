package julius.game.chessengine.tuning;

/**
 * Aggregates all tunable aspiration-window parameters so the search can capture a stable view
 * of the configuration at construction time. The values are loaded from {@link Tuning} which in
 * turn reflects the active {@link ParameterRegistry} snapshot.
 */
public final class AspirationParameters {

    private AspirationParameters() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                Tuning.searchAspMinSpanCp(),
                Tuning.searchAspMaxSpanCp(),
                Tuning.searchAspDefaultSpanCp(),
                Tuning.searchAspHistoryBlend(),
                Tuning.searchAspMomentumStepCp(),
                Tuning.searchAspMomentumCap(),
                Tuning.searchAspFailureRatio(),
                Tuning.searchAspBaseOffsetCp(),
                Tuning.searchAspSwingWeight(),
                Tuning.searchAspVolatilityWeight(),
                Tuning.searchAspDepthScale(),
                Tuning.searchAspDepthPivot(),
                Tuning.searchAspFloorBaseCp(),
                Tuning.searchAspFloorVolWeight(),
                Tuning.searchAspFloorStreakStepCp(),
                Tuning.searchAspBumpBaseCp(),
                Tuning.searchAspBumpStreakCp(),
                Tuning.searchAspBumpDepthCp(),
                Tuning.searchAspFullWindowScale(),
                Tuning.searchAspLastSpanScale(),
                Tuning.searchAspFullWindowMinMultiplier(),
                Tuning.searchAspBlendBaselineWeight(),
                Tuning.searchAspBlendCandidateWeight(),
                Tuning.searchAspMaxRetriesBase(),
                Tuning.searchAspMaxRetriesVolThresholdHigh(),
                Tuning.searchAspMaxRetriesVolBonusHigh(),
                Tuning.searchAspMaxRetriesVolThresholdMed(),
                Tuning.searchAspMaxRetriesVolBonusMed(),
                Tuning.searchAspMaxRetriesDepthOffset(),
                Tuning.searchAspMaxRetriesDepthDivisor(),
                Tuning.searchAspMaxRetriesMomentumDivisor(),
                Tuning.searchAspMaxRetriesMin(),
                Tuning.searchAspMaxRetriesMax()
        );
    }

    public record Snapshot(
            int minSpanCp,
            int maxSpanCp,
            int defaultSpanCp,
            double historyBlend,
            int momentumStepCp,
            int momentumCap,
            double failureRatio,
            int baseOffsetCp,
            double swingWeight,
            double volatilityWeight,
            double depthScale,
            int depthPivot,
            int floorBaseCp,
            double floorVolWeight,
            int floorStreakStepCp,
            int bumpBaseCp,
            int bumpStreakCp,
            int bumpDepthCp,
            double fullWindowScale,
            double lastSpanScale,
            double fullWindowMinMultiplier,
            double blendBaselineWeight,
            double blendCandidateWeight,
            int maxRetriesBase,
            int maxRetriesVolThresholdHigh,
            int maxRetriesVolBonusHigh,
            int maxRetriesVolThresholdMed,
            int maxRetriesVolBonusMed,
            int maxRetriesDepthOffset,
            int maxRetriesDepthDivisor,
            int maxRetriesMomentumDivisor,
            int maxRetriesMin,
            int maxRetriesMax
    ) {
    }
}
