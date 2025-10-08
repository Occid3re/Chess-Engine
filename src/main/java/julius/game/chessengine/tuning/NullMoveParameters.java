package julius.game.chessengine.tuning;

/**
 * Encapsulates the tunable coefficients that drive null-move pruning and verification.
 */
public final class NullMoveParameters {

    private NullMoveParameters() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                Tuning.searchNullBaseReduction(),
                Tuning.searchNullDepthWeight(),
                Tuning.searchNullMaterialWeight(),
                Tuning.searchNullMobilityWeight(),
                Tuning.searchNullDepthCap(),
                Tuning.searchNullMaterialCap(),
                Tuning.searchNullMobilityCap(),
                Tuning.searchNullLowMaterialThreshold(),
                Tuning.searchNullLowMobilityThreshold(),
                Tuning.searchNullVeryLowMobilityThreshold(),
                Tuning.searchNullLowMaterialPenalty(),
                Tuning.searchNullVeryLowMobilityPenalty(),
                Tuning.searchNullSwingGuardMinCp(),
                Tuning.searchNullSwingGuardDivisor()
        );
    }

    public record Snapshot(
            double baseReduction,
            double depthWeight,
            double materialWeight,
            double mobilityWeight,
            int depthCap,
            int materialCap,
            int mobilityCap,
            int lowMaterialThreshold,
            int lowMobilityThreshold,
            int veryLowMobilityThreshold,
            double lowMaterialPenalty,
            double veryLowMobilityPenalty,
            double swingGuardMinCp,
            double swingGuardDivisor
    ) {
    }
}
