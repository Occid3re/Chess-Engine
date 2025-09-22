package julius.game.chessengine.ai;

import java.util.Locale;

/**
 * Immutable snapshot of search instrumentation captured after a completed search task.
 */
public record SearchDiagnostics(
        int bestDepth,
        double bestScore,
        int deepestPlyVisited,
        int deepestQuiescencePly,
        int rootMovesGenerated,
        int rootMovesExplored,
        long rootBetaCutoffs,
        long iterationsCompleted,
        long aspirationFailHighs,
        long aspirationFailLows,
        long aspirationResets,
        long transpositionLookups,
        long transpositionHits,
        long transpositionExactHits,
        long transpositionCutoffs,
        long nullMoveTries,
        long nullMovePrunes,
        long nullMoveVerifications,
        long nullMoveVerificationFails,
        long lateMoveReductions,
        long lateMoveReductionSum,
        long lateMovePrunes,
        long futilityPrunes,
        long betaCutoffs,
        long staticEvalCalls,
        long quiescenceNodes,
        long quiescenceStandPatCuts,
        long quiescenceDeltaPrunes,
        long quiescenceSeePrunes,
        long quiescenceCaptures
) {
    public static final SearchDiagnostics EMPTY = new SearchDiagnostics(
            0,
            Double.NaN,
            0,
            0,
            0,
            0,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L
    );

    public String formatAverageLmrReduction() {
        if (lateMoveReductions <= 0L) {
            return "0.0";
        }
        double avg = (double) lateMoveReductionSum / (double) lateMoveReductions;
        return String.format(Locale.US, "%.2f", avg);
    }
}
