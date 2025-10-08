package julius.game.chessengine.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import julius.game.chessengine.tuning.AspirationParameters;

/**
 * Aspiration controller in *centipawns* (ints).
 * - Tight spans driven by tunable parameters
 * - Additive growth on fail-high/low (no explosive multipliers)
 * - Gentle EMA blending across iterations
 * - Momentum nudges are small and tunable
 */
final class AspirationController {

    private static final int MAX_HISTORY = 12;

    private final AspirationParameters.Snapshot params;
    private final Deque<Integer> recentScoresCp = new ArrayDeque<>(MAX_HISTORY);

    private int lastWindowSpanCp;
    private int failLowMomentum;
    private int failHighMomentum;
    private State currentState;

    AspirationController() {
        this(AspirationParameters.snapshot());
    }

    AspirationController(AspirationParameters.Snapshot params) {
        this.params = params;
        this.lastWindowSpanCp = clampSpanCp(params.defaultSpanCp());
    }

    State beginDepth(int depth, double lastScore /*in cp*/, java.util.SplittableRandom ignored) {
        final int lastCp = (int) Math.round(lastScore); // enforce cp
        final int volatilityCp = estimateVolatilityCp();
        final int swingCp = estimateAverageSwingCp();

        int baseSpan = computeBaseSpanCp(depth, volatilityCp, swingCp);

        final int lower = clampSpanCp(baseSpan + failLowMomentum * params.momentumStepCp());
        final int upper = clampSpanCp(baseSpan + failHighMomentum * params.momentumStepCp());

        final int maxRetries = computeMaxRetries(depth, volatilityCp);
        currentState = new State(depth, lastCp, lower, upper, maxRetries);
        return currentState;
    }

    Adjustment onFailLow(double candidateScore /*in cp*/) {
        if (currentState == null) return Adjustment.fullWindow();

        currentState.retries++;
        currentState.failLowStreak++;
        currentState.failHighStreak = 0;
        failLowMomentum = Math.min(params.momentumCap(), failLowMomentum + 1);
        failHighMomentum = Math.max(0, failHighMomentum - 1);

        final int cand = (int) Math.round(candidateScore);
        final int vol = estimateVolatilityCp();

        final int floor = clampSpanCp((int) Math.round(
                params.floorBaseCp()
                        + params.floorVolWeight() * vol
                        + params.floorStreakStepCp() * currentState.failLowStreak));

        final int bump = (int) Math.round(
                params.bumpBaseCp()
                        + params.bumpStreakCp() * currentState.failLowStreak
                        + Math.max(0, currentState.depth - params.depthPivot()) * params.bumpDepthCp());
        currentState.lowerSpan = clampSpanCp(Math.max(currentState.lowerSpan + bump, floor));

        currentState.center = Math.min(currentState.center, cand);

        currentState.upperSpan = Math.max(currentState.upperSpan,
                (int) Math.round(currentState.lowerSpan * params.failureRatio()));

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
        failHighMomentum = Math.min(params.momentumCap(), failHighMomentum + 1);
        failLowMomentum = Math.max(0, failLowMomentum - 1);

        final int cand = (int) Math.round(candidateScore);
        final int vol = estimateVolatilityCp();

        final int floor = clampSpanCp((int) Math.round(
                params.floorBaseCp()
                        + params.floorVolWeight() * vol
                        + params.floorStreakStepCp() * currentState.failHighStreak));
        final int bump = (int) Math.round(
                params.bumpBaseCp()
                        + params.bumpStreakCp() * currentState.failHighStreak
                        + Math.max(0, currentState.depth - params.depthPivot()) * params.bumpDepthCp());
        currentState.upperSpan = clampSpanCp(Math.max(currentState.upperSpan + bump, floor));

        currentState.center = Math.max(currentState.center, cand);

        currentState.lowerSpan = Math.max(currentState.lowerSpan,
                (int) Math.round(currentState.upperSpan * params.failureRatio()));

        if (currentState.retries >= currentState.maxRetries) {
            return Adjustment.fullWindow();
        }
        return Adjustment.window(currentState.alpha(), currentState.beta());
    }

    void onSuccess(double bestScore /*in cp*/) {
        if (currentState == null) return;
        currentState.center = (int) Math.round(bestScore);
        currentState.success = true;
        currentState.failHighStreak = 0;
        currentState.failLowStreak = 0;
        failLowMomentum = Math.max(0, failLowMomentum - 1);
        failHighMomentum = Math.max(0, failHighMomentum - 1);
    }

    void finishIteration(double score /*in cp*/, boolean attemptedAspiration, boolean usedFullWindow) {
        final int cp = (int) Math.round(score);

        if (attemptedAspiration) {
            int candidateSpan = lastWindowSpanCp;
            if (currentState != null) {
                candidateSpan = (currentState.lowerSpan + currentState.upperSpan) >>> 1;
                if (usedFullWindow) {
                    lastWindowSpanCp = adjustAfterFullWindowCp(candidateSpan);
                } else {
                    lastWindowSpanCp = blendSpanCp(lastWindowSpanCp, candidateSpan);
                }
            } else if (usedFullWindow) {
                lastWindowSpanCp = adjustAfterFullWindowCp(lastWindowSpanCp);
            }
        } else {
            lastWindowSpanCp = blendSpanCp(lastWindowSpanCp, lastWindowSpanCp);
        }

        pushScoreCp(cp);
        currentState = null;
    }

    // ---- internals ----------------------------------------------------------

    private int computeBaseSpanCp(int depth, int volatilityCp, int swingCp) {
        double baseline = params.baseOffsetCp()
                + params.swingWeight() * swingCp
                + params.volatilityWeight() * volatilityCp;

        double emaBlend = Math.max(0.0, 1.0 - params.historyBlend());
        double ema = baseline * emaBlend + lastWindowSpanCp * params.historyBlend();

        double depthMultiplier = 1.0 + params.depthScale() * Math.max(0, depth - params.depthPivot());
        double candidate = Math.max(ema, ema * depthMultiplier);
        return clampSpanCp((int) Math.round(candidate));
    }

    private int computeMaxRetries(int depth, int volatilityCp) {
        int base = params.maxRetriesBase();
        if (volatilityCp > params.maxRetriesVolThresholdHigh()) {
            base += params.maxRetriesVolBonusHigh();
        } else if (volatilityCp > params.maxRetriesVolThresholdMed()) {
            base += params.maxRetriesVolBonusMed();
        }
        int depthDivisor = Math.max(1, params.maxRetriesDepthDivisor());
        base += Math.max(0, depth - params.maxRetriesDepthOffset()) / depthDivisor;
        int momentumDivisor = Math.max(1, params.maxRetriesMomentumDivisor());
        base += Math.max(failLowMomentum, failHighMomentum) / momentumDivisor;
        return Math.min(params.maxRetriesMax(), Math.max(params.maxRetriesMin(), base));
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
        return (int) Math.round(Math.sqrt(Math.max(var, 0.0)));
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
        return count == 0 ? 0 : (int) (total / count);
    }

    private int clampSpanCp(int span) {
        if (span <= 0) return params.defaultSpanCp();
        return Math.max(params.minSpanCp(), Math.min(params.maxSpanCp(), span));
    }

    private int blendSpanCp(int baselineCp, int candidateCp) {
        if (candidateCp <= 0) return baselineCp;
        if (baselineCp <= 0) return clampSpanCp(candidateCp);
        double baselineWeight = Math.max(0.0, params.blendBaselineWeight());
        double candidateWeight = Math.max(0.0, params.blendCandidateWeight());
        double sum = baselineWeight + candidateWeight;
        if (sum == 0.0) {
            baselineWeight = 0.5;
            candidateWeight = 0.5;
            sum = 1.0;
        }
        final int mixed = (int) Math.round((baselineCp * baselineWeight + candidateCp * candidateWeight) / sum);
        return clampSpanCp(mixed);
    }

    private int adjustAfterFullWindowCp(int candidateCp) {
        int minScaled = (int) Math.round(params.minSpanCp() * params.fullWindowMinMultiplier());
        int base = candidateCp > 0 ? candidateCp : Math.max(lastWindowSpanCp, minScaled);
        int scaled = clampSpanCp((int) Math.round(base * params.fullWindowScale()));
        if (lastWindowSpanCp <= 0) return scaled;
        return Math.max(clampSpanCp((int) Math.round(lastWindowSpanCp * params.lastSpanScale())), scaled);
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
