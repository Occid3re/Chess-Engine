package julius.game.chessengine.evaluation;

import julius.game.chessengine.helper.KingHelper;
import julius.game.chessengine.tuning.Tuning;

import java.util.Objects;

/**
 * Experimental evaluation module that distills board tension into a compact "alien" score. The
 * module mixes bitboard fingerprints through a small hashing stage so that subtle structural
 * differences ripple into the returned score without exposing the exact formula. The numeric
 * controls are fully tunable via {@link Tuning}, allowing the auto tuner to mutate the behaviour
 * without needing to understand the internal mixing strategy.
 */
public final class AlienScoreModule implements EvaluationModule {

    private static final int MAX_FLUX_MAGNITUDE = 4096;

    private final int baseOffset;
    private final int fluxDivisor;
    private final int midgameFluxWeight;
    private final int endgameFluxWeight;
    private final int midgameTensionWeight;
    private final int endgameTensionWeight;
    private final int midgameSupportWeight;
    private final int endgameSupportWeight;
    private final int midgameKingPressureWeight;
    private final int endgameKingPressureWeight;
    private final int tempoBonus;

    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;

    public AlienScoreModule() {
        this.baseOffset = Tuning.alienBaseOffset();
        this.fluxDivisor = Math.max(1, Tuning.alienFluxDivisor());
        this.midgameFluxWeight = Tuning.alienMidgameFluxWeight();
        this.endgameFluxWeight = Tuning.alienEndgameFluxWeight();
        this.midgameTensionWeight = Tuning.alienMidgameTensionWeight();
        this.endgameTensionWeight = Tuning.alienEndgameTensionWeight();
        this.midgameSupportWeight = Tuning.alienMidgameSupportWeight();
        this.endgameSupportWeight = Tuning.alienEndgameSupportWeight();
        this.midgameKingPressureWeight = Tuning.alienMidgameKingPressureWeight();
        this.endgameKingPressureWeight = Tuning.alienEndgameKingPressureWeight();
        this.tempoBonus = Tuning.alienTempoBonus();
    }

    @Override
    public void initialize(EvaluationContext context) {
        recomputeScores(context);
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        recomputeScores(context);
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        dirty = true;
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        dirty = true;
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

    private void recomputeScores(EvaluationContext context) {
        Objects.requireNonNull(context, "context");

        long whitePieces = context.getWhitePieces();
        long blackPieces = context.getBlackPieces();
        long whiteAttacks = context.getWhiteAttackMap();
        long blackAttacks = context.getBlackAttackMap();

        int fluxDelta = computeFluxDelta(whitePieces, whiteAttacks, blackPieces, blackAttacks);
        int normalizedFlux = clampFlux(fluxDelta / fluxDivisor);

        int whiteUnderFire = Long.bitCount(whitePieces & blackAttacks);
        int blackUnderFire = Long.bitCount(blackPieces & whiteAttacks);
        int tensionDiff = blackUnderFire - whiteUnderFire;

        int whiteSupport = Long.bitCount(whitePieces & whiteAttacks);
        int blackSupport = Long.bitCount(blackPieces & blackAttacks);
        int supportDiff = whiteSupport - blackSupport;

        int whiteKingPressure = Long.bitCount(blackAttacks & kingZone(context.getWhiteKing()));
        int blackKingPressure = Long.bitCount(whiteAttacks & kingZone(context.getBlackKing()));
        int kingPressureDiff = blackKingPressure - whiteKingPressure;

        int tempo = context.isWhiteToMove() ? tempoBonus : -tempoBonus;

        midgameScoreCache = normalizedFlux * midgameFluxWeight
                + tensionDiff * midgameTensionWeight
                + supportDiff * midgameSupportWeight
                + kingPressureDiff * midgameKingPressureWeight
                + tempo;

        endgameScoreCache = normalizedFlux * endgameFluxWeight
                + tensionDiff * endgameTensionWeight
                + supportDiff * endgameSupportWeight
                + kingPressureDiff * endgameKingPressureWeight
                + tempo;

        dirty = false;
    }

    private int clampFlux(int value) {
        if (value > MAX_FLUX_MAGNITUDE) {
            return MAX_FLUX_MAGNITUDE;
        }
        if (value < -MAX_FLUX_MAGNITUDE) {
            return -MAX_FLUX_MAGNITUDE;
        }
        return value;
    }

    private int computeFluxDelta(long whitePieces, long whiteAttacks,
                                 long blackPieces, long blackAttacks) {
        int whiteSignature = scramble(whitePieces, whiteAttacks);
        int blackSignature = scramble(blackPieces, blackAttacks);
        return whiteSignature - blackSignature;
    }

    private int scramble(long pieces, long attacks) {
        long mix = pieces ^ Long.rotateLeft(attacks, 23);
        mix ^= Long.rotateLeft(pieces, 7);
        mix += Integer.toUnsignedLong(baseOffset);
        mix += (long) Long.bitCount(pieces) << 32;
        mix += (long) Long.bitCount(attacks) << 24;
        mix += (long) Long.bitCount(pieces & attacks) << 16;
        mix ^= mix >>> 33;
        mix *= 0xff51afd7ed558ccdL;
        mix ^= mix >>> 33;
        mix *= 0xc4ceb9fe1a85ec53L;
        mix ^= mix >>> 33;
        return (int) mix;
    }

    private static long kingZone(long king) {
        if (king == 0L) {
            return 0L;
        }
        int index = Long.numberOfTrailingZeros(king);
        return KingHelper.KING_ATTACKS[index] | king;
    }
}
