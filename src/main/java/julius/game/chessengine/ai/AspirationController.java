package julius.game.chessengine.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.SplittableRandom;

/**
 * Tracks aspiration-window parameters across iterative deepening.
 *
 * <p>The controller smooths the window span using the score volatility observed in
 * the diagnostic traces from BestMoveSearchTest so that fail-high/low streaks converge quickly without
 * falling back to a full alpha/beta window on every iteration. The heuristics favour
 * slightly wider retries when the score swings or volatility spike, while rewarding
 * steady positions with narrower, faster windows.</p>
 */
final class AspirationController {

    private static final int MAX_HISTORY = 12;
    private static final double MIN_SPAN = 14.0;
    private static final double MAX_SPAN = 720.0;
    private static final double DEFAULT_SPAN = 48.0;
    private static final double MOMENTUM_INFLATION = 0.15;
    private static final double HISTORY_BLEND = 0.45;
    private static final double FAILURE_RATIO = 0.65;

    private final Deque<Double> recentScores = new ArrayDeque<>(MAX_HISTORY);

    private double lastWindowSpan = DEFAULT_SPAN;
    private int failLowMomentum;
    private int failHighMomentum;
    private State currentState;

    AspirationController() {
    }

    State beginDepth(int depth, double lastScore, SplittableRandom rng) {
        double volatility = estimateVolatility();
        double swing = estimateAverageSwing();
        double baseSpan = computeBaseSpan(depth, volatility, swing);
        if (rng != null) {
            double jitter = rng.nextDouble(-4.0, 4.0);
            baseSpan = clampSpan(baseSpan + jitter);
        }
        double lower = clampSpan(baseSpan * (1.0 + MOMENTUM_INFLATION * failLowMomentum));
        double upper = clampSpan(baseSpan * (1.0 + MOMENTUM_INFLATION * failHighMomentum));
        int maxRetries = computeMaxRetries(depth, volatility);
        currentState = new State(depth, lastScore, lower, upper, maxRetries);
        return currentState;
    }

    Adjustment onFailLow(double candidateScore) {
        if (currentState == null) {
            return Adjustment.fullWindow();
        }
        currentState.retries++;
        currentState.failLowStreak++;
        currentState.failHighStreak = 0;
        failLowMomentum = Math.min(8, failLowMomentum + 1);
        failHighMomentum = Math.max(0, failHighMomentum - 1);

        double volatility = estimateVolatility();
        double floor = clampSpan((volatility * (1.25 + 0.12 * currentState.failLowStreak)) + 14.0);
        double growth = 1.45 + 0.22 * currentState.failLowStreak
                + 0.04 * Math.max(0, currentState.depth - 3);
        double historyBoost = 1.0 + 0.10 * failLowMomentum;
        currentState.lowerSpan = clampSpan(Math.max(currentState.lowerSpan * growth, floor) * historyBoost);
        currentState.center = Math.min(currentState.center, candidateScore);
        currentState.upperSpan = Math.max(currentState.upperSpan, currentState.lowerSpan * FAILURE_RATIO);

        if (currentState.retries >= currentState.maxRetries) {
            return Adjustment.fullWindow();
        }
        return Adjustment.window(currentState.alpha(), currentState.beta());
    }

    Adjustment onFailHigh(double candidateScore) {
        if (currentState == null) {
            return Adjustment.fullWindow();
        }
        currentState.retries++;
        currentState.failHighStreak++;
        currentState.failLowStreak = 0;
        failHighMomentum = Math.min(8, failHighMomentum + 1);
        failLowMomentum = Math.max(0, failLowMomentum - 1);

        double volatility = estimateVolatility();
        double floor = clampSpan((volatility * (1.25 + 0.12 * currentState.failHighStreak)) + 14.0);
        double growth = 1.45 + 0.22 * currentState.failHighStreak
                + 0.04 * Math.max(0, currentState.depth - 3);
        double historyBoost = 1.0 + 0.10 * failHighMomentum;
        currentState.upperSpan = clampSpan(Math.max(currentState.upperSpan * growth, floor) * historyBoost);
        currentState.center = Math.max(currentState.center, candidateScore);
        currentState.lowerSpan = Math.max(currentState.lowerSpan, currentState.upperSpan * FAILURE_RATIO);

        if (currentState.retries >= currentState.maxRetries) {
            return Adjustment.fullWindow();
        }
        return Adjustment.window(currentState.alpha(), currentState.beta());
    }

    void onSuccess(double bestScore) {
        if (currentState == null) {
            return;
        }
        currentState.center = bestScore;
        currentState.success = true;
        currentState.failHighStreak = 0;
        currentState.failLowStreak = 0;
        failLowMomentum = Math.max(0, failLowMomentum - 1);
        failHighMomentum = Math.max(0, failHighMomentum - 1);
    }

    void finishIteration(double score, boolean attemptedAspiration, boolean usedFullWindow) {
        if (attemptedAspiration) {
            double candidateSpan = Double.NaN;
            if (currentState != null) {
                candidateSpan = (currentState.lowerSpan + currentState.upperSpan) * 0.5;
                if (usedFullWindow) {
                    lastWindowSpan = adjustAfterFullWindow(candidateSpan);
                } else if (currentState.success) {
                    lastWindowSpan = blendSpan(lastWindowSpan, candidateSpan);
                }
            } else if (usedFullWindow) {
                lastWindowSpan = adjustAfterFullWindow(lastWindowSpan);
            }
            if (!usedFullWindow && currentState != null && !currentState.success) {
                lastWindowSpan = blendSpan(lastWindowSpan, candidateSpan);
            }
        } else {
            lastWindowSpan = blendSpan(lastWindowSpan, lastWindowSpan);
        }
        pushScore(score);
        currentState = null;
    }

    private double computeBaseSpan(int depth, double volatility, double swing) {
        double baseline = 26.0 + 0.4 * swing + 1.2 * volatility;
        if (lastWindowSpan > 0.0 && Double.isFinite(lastWindowSpan)) {
            baseline = baseline * (1.0 - HISTORY_BLEND) + lastWindowSpan * HISTORY_BLEND;
        } else {
            baseline = Math.max(baseline, DEFAULT_SPAN);
        }
        double depthFactor = 1.0 + 0.06 * Math.max(0, depth - 3);
        baseline *= depthFactor;
        return clampSpan(baseline);
    }

    private int computeMaxRetries(int depth, double volatility) {
        int base = 4;
        if (volatility > 60.0) {
            base += 2;
        } else if (volatility > 25.0) {
            base += 1;
        }
        base += Math.max(0, depth - 4) / 2;
        base += Math.max(failLowMomentum, failHighMomentum) / 2;
        return Math.min(10, Math.max(3, base));
    }

    private void pushScore(double score) {
        if (!Double.isFinite(score)) {
            return;
        }
        if (recentScores.size() == MAX_HISTORY) {
            recentScores.removeFirst();
        }
        recentScores.addLast(score);
    }

    private double estimateVolatility() {
        if (recentScores.size() < 2) {
            return 0.0;
        }
        double mean = 0.0;
        for (double score : recentScores) {
            mean += score;
        }
        mean /= recentScores.size();
        double variance = 0.0;
        for (double score : recentScores) {
            double diff = score - mean;
            variance += diff * diff;
        }
        variance /= (recentScores.size() - 1);
        return Math.sqrt(Math.max(variance, 0.0));
    }

    private double estimateAverageSwing() {
        if (recentScores.size() < 2) {
            return 0.0;
        }
        Iterator<Double> it = recentScores.iterator();
        double prev = it.next();
        double total = 0.0;
        int count = 0;
        while (it.hasNext()) {
            double current = it.next();
            total += Math.abs(current - prev);
            prev = current;
            count++;
        }
        return count == 0 ? 0.0 : total / count;
    }

    private static double clampSpan(double span) {
        if (Double.isNaN(span) || Double.isInfinite(span)) {
            return DEFAULT_SPAN;
        }
        return Math.max(MIN_SPAN, Math.min(MAX_SPAN, span));
    }

    private double blendSpan(double baseline, double candidate) {
        if (!Double.isFinite(candidate) || candidate <= 0.0) {
            return baseline;
        }
        if (!Double.isFinite(baseline) || baseline <= 0.0) {
            return clampSpan(candidate);
        }
        return clampSpan(baseline * 0.55 + candidate * 0.45);
    }

    private double adjustAfterFullWindow(double candidate) {
        double base = candidate;
        if (!Double.isFinite(base) || base <= 0.0) {
            base = Math.max(lastWindowSpan, MIN_SPAN * 2.0);
        }
        double scaled = clampSpan(base * 1.18);
        if (!Double.isFinite(lastWindowSpan) || lastWindowSpan <= 0.0) {
            return scaled;
        }
        return Math.max(clampSpan(lastWindowSpan * 1.12), scaled);
    }

    static final class Adjustment {
        private final double alpha;
        private final double beta;
        private final boolean fullWindow;

        private Adjustment(double alpha, double beta, boolean fullWindow) {
            this.alpha = alpha;
            this.beta = beta;
            this.fullWindow = fullWindow;
        }

        static Adjustment window(double alpha, double beta) {
            return new Adjustment(alpha, beta, false);
        }

        static Adjustment fullWindow() {
            return new Adjustment(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, true);
        }

        double alpha() {
            return alpha;
        }

        double beta() {
            return beta;
        }

        boolean isFullWindow() {
            return fullWindow;
        }
    }

    static final class State {
        final int depth;
        double center;
        double lowerSpan;
        double upperSpan;
        final int maxRetries;
        int retries;
        int failLowStreak;
        int failHighStreak;
        boolean success;

        State(int depth, double center, double lowerSpan, double upperSpan, int maxRetries) {
            this.depth = depth;
            this.center = center;
            this.lowerSpan = lowerSpan;
            this.upperSpan = upperSpan;
            this.maxRetries = maxRetries;
        }

        double alpha() {
            return center - lowerSpan;
        }

        double beta() {
            return center + upperSpan;
        }
    }
}

