package julius.game.chessengine.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mutable collector for search heuristics metrics. Instances are attached to a {@link SearchTask}
 * and accumulate counts across all worker threads involved in the search. Once the search is
 * finished the values are frozen into a {@link SearchDiagnostics} snapshot.
 */
final class SearchInstrumentation {

    private static final SearchInstrumentation DISABLED = new SearchInstrumentation(false);

    static SearchInstrumentation disabled() {
        return DISABLED;
    }

    static SearchInstrumentation enabled() {
        return new SearchInstrumentation(true);
    }

    private final boolean enabled;

    private final AtomicInteger bestDepth = new AtomicInteger();
    private final AtomicInteger deepestPlyVisited = new AtomicInteger();
    private final AtomicInteger deepestQuiescencePly = new AtomicInteger();

    private final AtomicInteger rootMovesGenerated = new AtomicInteger();
    private final AtomicInteger rootMovesExplored = new AtomicInteger();
    private final LongAdder rootBetaCutoffs = new LongAdder();
    private final LongAdder iterationsCompleted = new LongAdder();

    private final LongAdder aspirationFailHighs = new LongAdder();
    private final LongAdder aspirationFailLows = new LongAdder();
    private final LongAdder aspirationResets = new LongAdder();

    private final LongAdder transpositionLookups = new LongAdder();
    private final LongAdder transpositionHits = new LongAdder();
    private final LongAdder transpositionExactHits = new LongAdder();
    private final LongAdder transpositionCutoffs = new LongAdder();

    private final LongAdder nullMoveTries = new LongAdder();
    private final LongAdder nullMovePrunes = new LongAdder();
    private final LongAdder nullMoveVerifications = new LongAdder();
    private final LongAdder nullMoveVerificationFails = new LongAdder();

    private final LongAdder lateMoveReductions = new LongAdder();
    private final LongAdder lateMoveReductionSum = new LongAdder();
    private final LongAdder lateMovePrunes = new LongAdder();
    private final LongAdder futilityPrunes = new LongAdder();

    private final LongAdder betaCutoffs = new LongAdder();
    private final LongAdder staticEvalCalls = new LongAdder();

    private final LongAdder quiescenceNodes = new LongAdder();
    private final LongAdder quiescenceStandPatCuts = new LongAdder();
    private final LongAdder quiescenceDeltaPrunes = new LongAdder();
    private final LongAdder quiescenceSeePrunes = new LongAdder();
    private final LongAdder quiescenceCaptures = new LongAdder();

    private SearchInstrumentation(boolean enabled) {
        this.enabled = enabled;
    }

    boolean isEnabled() {
        return enabled;
    }

    void recordIterationComplete(int depth) {
        if (!enabled) return;
        iterationsCompleted.increment();
        bestDepth.accumulateAndGet(depth, Math::max);
    }

    void recordAspirationFailHigh() {
        if (!enabled) return;
        aspirationFailHighs.increment();
    }

    void recordAspirationFailLow() {
        if (!enabled) return;
        aspirationFailLows.increment();
    }

    void recordAspirationReset() {
        if (!enabled) return;
        aspirationResets.increment();
    }

    void recordRootMovesGenerated(int count) {
        if (!enabled) return;
        rootMovesGenerated.set(Math.max(0, count));
    }

    void recordRootMoveExplored() {
        if (!enabled) return;
        rootMovesExplored.incrementAndGet();
    }

    void recordRootBetaCutoff() {
        if (!enabled) return;
        rootBetaCutoffs.increment();
    }

    void recordVisitedPly(int ply) {
        if (!enabled) return;
        if (ply < 0) return;
        deepestPlyVisited.accumulateAndGet(ply, Math::max);
    }

    void recordQuiescenceNode(int depth) {
        if (!enabled) return;
        quiescenceNodes.increment();
        if (depth >= 0) {
            deepestQuiescencePly.accumulateAndGet(depth, Math::max);
        }
    }

    void recordQuiescenceStandPatCut() {
        if (!enabled) return;
        quiescenceStandPatCuts.increment();
    }

    void recordQuiescenceDeltaPrune() {
        if (!enabled) return;
        quiescenceDeltaPrunes.increment();
    }

    void recordQuiescenceSeePrune() {
        if (!enabled) return;
        quiescenceSeePrunes.increment();
    }

    void recordQuiescenceCaptureSearched() {
        if (!enabled) return;
        quiescenceCaptures.increment();
    }

    void recordTranspositionLookup() {
        if (!enabled) return;
        transpositionLookups.increment();
    }

    void recordTranspositionHit(NodeType type, boolean cutoff) {
        if (!enabled) return;
        transpositionHits.increment();
        if (type == NodeType.EXACT) {
            transpositionExactHits.increment();
        }
        if (cutoff) {
            transpositionCutoffs.increment();
        }
    }

    void recordNullMoveAttempt() {
        if (!enabled) return;
        nullMoveTries.increment();
    }

    void recordNullMovePrune() {
        if (!enabled) return;
        nullMovePrunes.increment();
    }

    void recordNullMoveVerification() {
        if (!enabled) return;
        nullMoveVerifications.increment();
    }

    void recordNullMoveVerificationFail() {
        if (!enabled) return;
        nullMoveVerificationFails.increment();
    }

    void recordLateMoveReduction(int reduction) {
        if (!enabled || reduction <= 0) return;
        lateMoveReductions.increment();
        lateMoveReductionSum.add(reduction);
    }

    void recordLateMovePrune() {
        if (!enabled) return;
        lateMovePrunes.increment();
    }

    void recordFutilityPrune() {
        if (!enabled) return;
        futilityPrunes.increment();
    }

    void recordBetaCutoff() {
        if (!enabled) return;
        betaCutoffs.increment();
    }

    void recordStaticEvalCall() {
        if (!enabled) return;
        staticEvalCalls.increment();
    }

    SearchDiagnostics snapshot(int depth, double bestScore) {
        if (!enabled) {
            return SearchDiagnostics.EMPTY;
        }
        return new SearchDiagnostics(
                Math.max(depth, bestDepth.get()),
                bestScore,
                deepestPlyVisited.get(),
                deepestQuiescencePly.get(),
                rootMovesGenerated.get(),
                rootMovesExplored.get(),
                rootBetaCutoffs.sum(),
                iterationsCompleted.sum(),
                aspirationFailHighs.sum(),
                aspirationFailLows.sum(),
                aspirationResets.sum(),
                transpositionLookups.sum(),
                transpositionHits.sum(),
                transpositionExactHits.sum(),
                transpositionCutoffs.sum(),
                nullMoveTries.sum(),
                nullMovePrunes.sum(),
                nullMoveVerifications.sum(),
                nullMoveVerificationFails.sum(),
                lateMoveReductions.sum(),
                lateMoveReductionSum.sum(),
                lateMovePrunes.sum(),
                futilityPrunes.sum(),
                betaCutoffs.sum(),
                staticEvalCalls.sum(),
                quiescenceNodes.sum(),
                quiescenceStandPatCuts.sum(),
                quiescenceDeltaPrunes.sum(),
                quiescenceSeePrunes.sum(),
                quiescenceCaptures.sum()
        );
    }
}
