package julius.game.chessengine.evaluation;

import julius.game.chessengine.tuning.Tuning;

import java.util.Objects;

/**
 * Encourages timely piece development, sensible queen deployment and
 * castling before the middlegame begins. The module applies lightweight
 * penalties for minors that remain on their starting squares, for early
 * queen adventures while undeveloped minors still linger, and for losing
 * castling rights without completing the castle. Conversely, it rewards
 * sides that have already castled.
 */
public final class DevelopmentModule implements EvaluationModule {

    private static final double MIN_QUEEN_SCALE = 0.55;

    private static final long WHITE_KNIGHT_START =
            bit("b1") | bit("g1");
    private static final long WHITE_BISHOP_START =
            bit("c1") | bit("f1");
    private static final long WHITE_MINOR_START =
            WHITE_KNIGHT_START | WHITE_BISHOP_START;
    private static final long WHITE_QUEEN_START = bit("d1");

    private static final long BLACK_KNIGHT_START =
            bit("b8") | bit("g8");
    private static final long BLACK_BISHOP_START =
            bit("c8") | bit("f8");
    private static final long BLACK_MINOR_START =
            BLACK_KNIGHT_START | BLACK_BISHOP_START;
    private static final long BLACK_QUEEN_START = bit("d8");

    private static final int WHITE_MINOR_COUNT = 4;
    private static final int BLACK_MINOR_COUNT = 4;

    private final int developmentPhaseThreshold;
    private final int queenDevelopmentPhaseThreshold;
    private final int undevelopedMinorPenalty;
    private final int earlyQueenPenaltyPerMinor;
    private final int minUndevelopedMinorsForQueenPenalty;
    private final int startPositionPenalty;
    private final int castlingBonus;
    private final int notCastledRookMovePenalty;
    private final int queenDisplacementPenalty;
    private final int queenUnderAttackPenalty;
    private final int queenHangingPenalty;

    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;
    private boolean initialized;

    public DevelopmentModule() {
        this.developmentPhaseThreshold = Tuning.developmentPhaseThreshold();
        this.queenDevelopmentPhaseThreshold = Tuning.queenDevelopmentPhaseThreshold();
        this.undevelopedMinorPenalty = Tuning.undevelopedMinorPenalty();
        this.earlyQueenPenaltyPerMinor = Tuning.earlyQueenDevelopmentPenaltyPerMinor();
        this.minUndevelopedMinorsForQueenPenalty = Tuning.minUndevelopedMinorsForQueenPenalty();
        this.startPositionPenalty = Tuning.startPositionPenalty();
        this.castlingBonus = Tuning.castlingBonus();
        this.notCastledRookMovePenalty = Tuning.notCastledRookMovePenalty();
        this.queenDisplacementPenalty = Tuning.queenDisplacementPenalty();
        this.queenUnderAttackPenalty = Tuning.queenUnderAttackPenalty();
        this.queenHangingPenalty = Tuning.queenHangingPenalty();
    }

    @Override
    public void initialize(EvaluationContext context) {
        Objects.requireNonNull(context, "context");
        recompute(context);
        initialized = true;
    }

    @Override
    public void evaluate(EvaluationContext context) {
        Objects.requireNonNull(context, "context");
        if (!dirty) {
            return;
        }
        recompute(context);
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        markDirty();
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        markDirty();
    }

    @Override
    public int getMidgameScore() {
        return midgameScoreCache;
    }

    @Override
    public int getEndgameScore() {
        return endgameScoreCache;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    private void recompute(EvaluationContext context) {
        if (context == null) {
            reset();
            return;
        }
        int whiteScore = evaluateSide(context, true);
        int blackScore = evaluateSide(context, false);
        midgameScoreCache = whiteScore - blackScore;
        endgameScoreCache = midgameScoreCache;
        dirty = false;
    }

    private int evaluateSide(EvaluationContext context, boolean white) {
        int phase = context.getPhase();
        int score = 0;
        double developmentScale = phaseScale(phase, developmentPhaseThreshold);
        double queenScale = phaseScale(phase, queenDevelopmentPhaseThreshold);
        double queenPenaltyScale = Math.max(queenScale, MIN_QUEEN_SCALE);

        long knights = white ? context.getWhiteKnights() : context.getBlackKnights();
        long bishops = white ? context.getWhiteBishops() : context.getBlackBishops();
        long queen = white ? context.getWhiteQueens() : context.getBlackQueens();
        long enemyAttacks = white ? context.getBlackAttackMap() : context.getWhiteAttackMap();
        long ownAttacks = white ? context.getWhiteAttackMap() : context.getBlackAttackMap();

        int undevelopedKnights = Long.bitCount(knights & (white ? WHITE_KNIGHT_START : BLACK_KNIGHT_START));
        int undevelopedBishops = Long.bitCount(bishops & (white ? WHITE_BISHOP_START : BLACK_BISHOP_START));
        int undevelopedMinors = undevelopedKnights + undevelopedBishops;

        if (developmentScale > 0.0) {
            int totalMinors = white ? WHITE_MINOR_COUNT : BLACK_MINOR_COUNT;
            int undevelopedPenalty = scaledPenalty(undevelopedMinors * undevelopedMinorPenalty, developmentScale);
            score -= undevelopedPenalty;
            if (undevelopedMinors == totalMinors && totalMinors > 0) {
                score -= scaledPenalty(startPositionPenalty, developmentScale);
            }
            if (!hasCastled(context, white)) {
                int lostRights = lostCastlingRights(context, white);
                if (lostRights > 0) {
                    score -= scaledPenalty(lostRights * notCastledRookMovePenalty, developmentScale);
                }
            }
        }

        if (queenScale > 0.0) {
            long queenStartMask = white ? WHITE_QUEEN_START : BLACK_QUEEN_START;
            boolean queenOnStart = (queen & queenStartMask) != 0;
            if (!queenOnStart && undevelopedMinors >= minUndevelopedMinorsForQueenPenalty) {
                score -= scaledPenalty(undevelopedMinors * earlyQueenPenaltyPerMinor, queenScale);
            }
            if (queenDisplacementPenalty > 0) {
                int displacement = computeQueenDisplacement(context, queen, white);
                if (displacement > 0) {
                    score -= scaledPenalty(displacement * queenDisplacementPenalty, queenPenaltyScale);
                }
            }
            long queenMask = queen;
            while (queenMask != 0L) {
                long bit = queenMask & -queenMask;
                queenMask ^= bit;
                if ((enemyAttacks & bit) != 0L) {
                    boolean defended = (ownAttacks & bit) != 0L;
                    int penaltyBase = defended ? queenUnderAttackPenalty : queenHangingPenalty;
                    if (penaltyBase > 0) {
                        int index = Long.numberOfTrailingZeros(bit);
                        int rank = index >>> 3;
                        int depth = white ? Math.max(0, rank - 3) : Math.max(0, 4 - rank);
                        int depthBonus = penaltyBase;
                        int penalty = penaltyBase + depth * depthBonus;
                        score -= scaledPenalty(penalty, queenPenaltyScale);
                    }
                }
            }
        }

        if (hasCastled(context, white)) {
            score += scaledPenalty(castlingBonus, Math.max(developmentScale, 0.25));
        }

        return score;
    }

    private boolean hasCastled(EvaluationContext context, boolean white) {
        return white ? context.isWhiteKingHasCastled() : context.isBlackKingHasCastled();
    }

    private int lostCastlingRights(EvaluationContext context, boolean white) {
        if (white) {
            if (context.isWhiteKingMoved()) {
                return 2;
            }
            int lost = 0;
            if (context.isWhiteRookA1Moved()) {
                lost++;
            }
            if (context.isWhiteRookH1Moved()) {
                lost++;
            }
            return lost;
        }
        if (context.isBlackKingMoved()) {
            return 2;
        }
        int lost = 0;
        if (context.isBlackRookA8Moved()) {
            lost++;
        }
        if (context.isBlackRookH8Moved()) {
            lost++;
        }
        return lost;
    }

    private void reset() {
        midgameScoreCache = 0;
        endgameScoreCache = 0;
        dirty = false;
    }

    private static long bit(String square) {
        if (square == null || square.length() != 2) {
            throw new IllegalArgumentException("Invalid square: " + square);
        }
        char file = square.charAt(0);
        char rankChar = square.charAt(1);
        int rank = Character.digit(rankChar, 10);
        if (rank < 1 || rank > 8 || file < 'a' || file > 'h') {
            throw new IllegalArgumentException("Invalid square: " + square);
        }
        int index = (rank - 1) * 8 + (file - 'a');
        return 1L << index;
    }

    private static int computeQueenDisplacement(EvaluationContext context, long queenMask, boolean white) {
        if (queenMask == 0L) {
            return 0;
        }
        long enemyAttacks = white ? context.getBlackAttackMap() : context.getWhiteAttackMap();
        long ownAttacks = white ? context.getWhiteAttackMap() : context.getBlackAttackMap();

        int total = 0;
        while (queenMask != 0L) {
            long bit = queenMask & -queenMask;
            queenMask ^= bit;

            int index = Long.numberOfTrailingZeros(bit);
            int file = index & 7;
            int rank = index >>> 3;

            int startFile = 3; // column 'd'
            int startRank = white ? 0 : 7;
            int displacement = Math.abs(file - startFile) + Math.abs(rank - startRank);
            int depth = white ? Math.max(0, rank - 3) : Math.max(0, 5 - rank);

            int contribution = displacement + 2;
            if (depth > 0) {
                contribution += depth * 3;
            }

            boolean enemyTargets = (enemyAttacks & bit) != 0L;
            boolean supported = (ownAttacks & bit) != 0L;
            if (enemyTargets) {
                contribution += supported ? 4 : 7;
            }

            contribution = Math.min(contribution, 12);

            total += contribution;
        }
        return total;
    }

    private static double phaseScale(int phase, int threshold) {
        if (threshold <= 0) {
            return 0.0;
        }
        int clamped = Math.max(0, Math.min(phase, threshold));
        return 1.0 - (clamped / (double) threshold);
    }

    private static int scaledPenalty(int value, double scale) {
        if (value <= 0 || scale <= 0.0) {
            return 0;
        }
        return (int) Math.round(value * scale);
    }
}
