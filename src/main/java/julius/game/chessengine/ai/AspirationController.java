package julius.game.chessengine.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Aspiration controller in *centipawns* (ints).
 * - Tight spans: 12..256 cp (default 48 cp)
 * - Additive growth on fail-high/low (no explosive multipliers)
 * - Gentle EMA blending across iterations
 * - Momentum nudges are small (8 cp steps)
 */
final class AspirationController {

    private static final int MAX_HISTORY = 12;

    // All spans are in CENTIPAWNS.
    private static final int MIN_SPAN_CP = 12;     // 0.12 pawns
    private static final int MAX_SPAN_CP = 256;    // 2.56 pawns
    private static final int DEFAULT_SPAN_CP = 48; // 0.48 pawns

    private static final double HISTORY_BLEND = 0.40; // EMA weight toward last span
    private static final int MOMENTUM_STEP_CP = 8;    // each momentum point adds ~0.08 pawns
    private static final double FAILURE_RATIO = 0.60; // keep other side at least 60% of grown side

    private final Deque<Integer> recentScoresCp = new ArrayDeque<>(MAX_HISTORY);

    private int lastWindowSpanCp = DEFAULT_SPAN_CP;
    private int failLowMomentum;   // 0..8
    private int failHighMomentum;  // 0..8
    private State currentState;

    AspirationController() {}

    State beginDepth(int depth, double lastScore /*in cp*/, java.util.SplittableRandom ignored) {
        final int lastCp = (int)Math.round(lastScore); // enforce cp
        final int volatilityCp = estimateVolatilityCp();
        final int swingCp = estimateAverageSwingCp();

        int baseSpan = computeBaseSpanCp(depth, volatilityCp, swingCp);

        // Momentum widens one side slightly
        final int lower = clampSpanCp(baseSpan + failLowMomentum * MOMENTUM_STEP_CP);
        final int upper = clampSpanCp(baseSpan + failHighMomentum * MOMENTUM_STEP_CP);

        final int maxRetries = computeMaxRetries(depth, volatilityCp);
        currentState = new State(depth, lastCp, lower, upper, maxRetries);
        return currentState;
    }

    Adjustment onFailLow(double candidateScore /*in cp*/) {
        if (currentState == null) return Adjustment.fullWindow();

        currentState.retries++;
        currentState.failLowStreak++;
        currentState.failHighStreak = 0;
        failLowMomentum = Math.min(8, failLowMomentum + 1);
        failHighMomentum = Math.max(0, failHighMomentum - 1);

        final int cand = (int)Math.round(candidateScore);
        final int vol = estimateVolatilityCp();

        // Floor grows slowly with volatility and streak
        final int floor = clampSpanCp(8 + (vol / 2) + 4 * currentState.failLowStreak);

        // Additive growth (no explosive multipliers)
        final int bump = 12 + 6 * currentState.failLowStreak + Math.max(0, currentState.depth - 3) * 2;
        currentState.lowerSpan = clampSpanCp(Math.max(currentState.lowerSpan + bump, floor));

        // Keep center on fail-low
        currentState.center = Math.min(currentState.center, cand);

        // Keep the opposite side at least FAILURE_RATIO of the grown side
        currentState.upperSpan = Math.max(currentState.upperSpan,
                (int)Math.round(currentState.lowerSpan * FAILURE_RATIO));

        if (currentState.retries >= currentState.maxRetries) {
            return Adjustment.fullWindow();
        }
        return Adjustment.window(currentState.alpha(), currentState.beta());
    }

    Adjustment onFailHigh(double candidateScore /*in cp*/) {
        if (currentState == null) return Adjustment.fullWindow();

        currentState.retries++;
        currentState.failHighStreak++;
        currentState.failLowStreak = 0;
        failHighMomentum = Math.min(8, failHighMomentum + 1);
        failLowMomentum = Math.max(0, failLowMomentum - 1);

        final int cand = (int)Math.round(candidateScore);
        final int vol = estimateVolatilityCp();

        final int floor = clampSpanCp(8 + (vol / 2) + 4 * currentState.failHighStreak);
        final int bump = 12 + 6 * currentState.failHighStreak + Math.max(0, currentState.depth - 3) * 2;
        currentState.upperSpan = clampSpanCp(Math.max(currentState.upperSpan + bump, floor));

        currentState.center = Math.max(currentState.center, cand);

        currentState.lowerSpan = Math.max(currentState.lowerSpan,
                (int)Math.round(currentState.upperSpan * FAILURE_RATIO));

        if (currentState.retries >= currentState.maxRetries) {
            return Adjustment.fullWindow();
        }
        return Adjustment.window(currentState.alpha(), currentState.beta());
    }

    void onSuccess(double bestScore /*in cp*/) {
        if (currentState == null) return;
        currentState.center = (int)Math.round(bestScore);
        currentState.success = true;
        currentState.failHighStreak = 0;
        currentState.failLowStreak = 0;
        failLowMomentum = Math.max(0, failLowMomentum - 1);
        failHighMomentum = Math.max(0, failHighMomentum - 1);
    }

    void finishIteration(double score /*in cp*/, boolean attemptedAspiration, boolean usedFullWindow) {
        final int cp = (int)Math.round(score);

        if (attemptedAspiration) {
            int candidateSpan = lastWindowSpanCp;
            if (currentState != null) {
                candidateSpan = (currentState.lowerSpan + currentState.upperSpan) >>> 1;
                if (usedFullWindow) {
                    lastWindowSpanCp = adjustAfterFullWindowCp(candidateSpan);
                } else if (currentState.success) {
                    lastWindowSpanCp = blendSpanCp(lastWindowSpanCp, candidateSpan);
                } else {
                    // failed but not full window: nudge toward what we tried
                    lastWindowSpanCp = blendSpanCp(lastWindowSpanCp, candidateSpan);
                }
            } else if (usedFullWindow) {
                lastWindowSpanCp = adjustAfterFullWindowCp(lastWindowSpanCp);
            }
        } else {
            // no aspiration: very small decay toward itself (no change effectively)
            lastWindowSpanCp = blendSpanCp(lastWindowSpanCp, lastWindowSpanCp);
        }

        pushScoreCp(cp);
        currentState = null;
    }

    // ---- internals ----------------------------------------------------------

    private int computeBaseSpanCp(int depth, int volatilityCp, int swingCp) {
        // Baseline: small + respond to recent noise
        int baseline = 24 + (int)(0.25 * swingCp) + (int)(0.6 * volatilityCp);

        // Mix with last achieved span (EMA)
        baseline = (int)Math.round(baseline * (1.0 - HISTORY_BLEND) + lastWindowSpanCp * HISTORY_BLEND);

        // Slightly wider with depth
        final int depthFactorCp = (int)Math.round(baseline * (1.0 + 0.04 * Math.max(0, depth - 3)));
        return clampSpanCp(Math.max(baseline, depthFactorCp));
    }

    private int computeMaxRetries(int depth, int volatilityCp) {
        int base = 3;
        if (volatilityCp > 120) base += 2;
        else if (volatilityCp > 60) base += 1;
        base += Math.max(0, depth - 4) / 2;
        base += Math.max(failLowMomentum, failHighMomentum) / 3;
        return Math.min(6, Math.max(3, base));
    }

    private void pushScoreCp(int cp) {
        if (cp == Integer.MIN_VALUE || cp == Integer.MAX_VALUE) return;
        if (recentScoresCp.size() == MAX_HISTORY) recentScoresCp.removeFirst();
        recentScoresCp.addLast(cp);
    }

    private int estimateVolatilityCp() {
        if (recentScoresCp.size() < 2) return 0;
        long sum = 0;
        for (int s : recentScoresCp) sum += s;
        final double mean = sum / (double) recentScoresCp.size();
        double var = 0.0;
        for (int s : recentScoresCp) {
            final double d = s - mean;
            var += d * d;
        }
        var /= (recentScoresCp.size() - 1);
        return (int)Math.round(Math.sqrt(Math.max(var, 0.0)));
    }

    private int estimateAverageSwingCp() {
        if (recentScoresCp.size() < 2) return 0;
        final Iterator<Integer> it = recentScoresCp.iterator();
        int prev = it.next();
        long total = 0;
        int count = 0;
        while (it.hasNext()) {
            int cur = it.next();
            total += Math.abs(cur - prev);
            prev = cur;
            count++;
        }
        return count == 0 ? 0 : (int)(total / count);
    }

    private static int clampSpanCp(int span) {
        if (span <= 0) return DEFAULT_SPAN_CP;
        return Math.max(MIN_SPAN_CP, Math.min(MAX_SPAN_CP, span));
    }

    private int blendSpanCp(int baselineCp, int candidateCp) {
        if (candidateCp <= 0) return baselineCp;
        if (baselineCp <= 0) return clampSpanCp(candidateCp);
        final int mixed = (int)Math.round(baselineCp * 0.60 + candidateCp * 0.40);
        return clampSpanCp(mixed);
    }

    private int adjustAfterFullWindowCp(int candidateCp) {
        int base = candidateCp > 0 ? candidateCp : Math.max(lastWindowSpanCp, MIN_SPAN_CP * 2);
        // modest +15% then clamp
        int scaled = clampSpanCp((int)Math.round(base * 1.15));
        if (lastWindowSpanCp <= 0) return scaled;
        return Math.max(clampSpanCp((int)Math.round(lastWindowSpanCp * 1.10)), scaled);
    }

    static final class Adjustment {
        private final double alpha;     // cp
        private final double beta;      // cp
        private final boolean fullWindow;
        private Adjustment(int alphaCp, int betaCp, boolean full) {
            this.alpha = alphaCp;
            this.beta = betaCp;
            this.fullWindow = full;
        }
        static Adjustment window(int alphaCp, int betaCp) {
            return new Adjustment(alphaCp, betaCp, false);
        }
        static Adjustment fullWindow() {
            return new Adjustment(Integer.MIN_VALUE, Integer.MAX_VALUE, true);
        }
        double alpha() { return alpha; }
        double beta() { return beta; }
        boolean isFullWindow() { return fullWindow; }
    }

    static final class State {
        final int depth;
        int center;          // cp
        int lowerSpan;       // cp
        int upperSpan;       // cp
        final int maxRetries;
        int retries;
        int failLowStreak;
        int failHighStreak;
        boolean success;

        State(int depth, int centerCp, int lowerSpanCp, int upperSpanCp, int maxRetries) {
            this.depth = depth;
            this.center = centerCp;
            this.lowerSpan = lowerSpanCp;
            this.upperSpan = upperSpanCp;
            this.maxRetries = maxRetries;
        }
        int alpha() { return center - lowerSpan; }
        int beta() { return center + upperSpan; }
    }
}
