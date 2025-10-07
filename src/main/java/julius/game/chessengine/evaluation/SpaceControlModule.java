package julius.game.chessengine.evaluation;

import julius.game.chessengine.helper.BitHelper;
import julius.game.chessengine.tuning.Tuning;

import java.util.Objects;

/**
 * Measures safe territory, outposts, and cramping pressure to capture long-term space advantages
 * that are not fully reflected in mobility or pawn-structure scores.
 */
public final class SpaceControlModule implements EvaluationModule {

    private static final long FILE_A = BitHelper.FileMasks[0];
    private static final long FILE_H = BitHelper.FileMasks[7];

    private static final long WHITE_FRONT_HALF = BitHelper.RankMasks[4]
            | BitHelper.RankMasks[5]
            | BitHelper.RankMasks[6]
            | BitHelper.RankMasks[7];
    private static final long BLACK_FRONT_HALF = BitHelper.RankMasks[0]
            | BitHelper.RankMasks[1]
            | BitHelper.RankMasks[2]
            | BitHelper.RankMasks[3];

    private static final long WHITE_HOME_HALF = BitHelper.RankMasks[0]
            | BitHelper.RankMasks[1]
            | BitHelper.RankMasks[2]
            | BitHelper.RankMasks[3];
    private static final long BLACK_HOME_HALF = BitHelper.RankMasks[4]
            | BitHelper.RankMasks[5]
            | BitHelper.RankMasks[6]
            | BitHelper.RankMasks[7];

    private static final long WHITE_OUTPOST_MASK = BitHelper.RankMasks[3]
            | BitHelper.RankMasks[4]
            | BitHelper.RankMasks[5];
    private static final long BLACK_OUTPOST_MASK = BitHelper.RankMasks[2]
            | BitHelper.RankMasks[3]
            | BitHelper.RankMasks[4];

    private int midgameScore;
    private int endgameScore;
    private boolean dirty = true;
    private boolean initialized;

    @Override
    public void initialize(EvaluationContext context) {
        recompute(Objects.requireNonNull(context, "context"));
        initialized = true;
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        recompute(Objects.requireNonNull(context, "context"));
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        Objects.requireNonNull(moveContext, "moveContext");
        if (!ensureInitialized(moveContext)) {
            return;
        }
        dirty = true;
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        Objects.requireNonNull(moveContext, "moveContext");
        applyMove(moveContext);
    }

    @Override
    public int getMidgameScore() {
        return midgameScore;
    }

    @Override
    public int getEndgameScore() {
        return endgameScore;
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
        long whiteAttack = context.getWhiteAttackMap();
        long blackAttack = context.getBlackAttackMap();
        long emptySquares = ~context.getAllPieces();

        long whiteSafe = WHITE_FRONT_HALF & emptySquares & whiteAttack & ~blackAttack;
        long blackSafe = BLACK_FRONT_HALF & emptySquares & blackAttack & ~whiteAttack;
        int safeDelta = Long.bitCount(whiteSafe) - Long.bitCount(blackSafe);

        long whiteCramped = WHITE_HOME_HALF & blackAttack & ~whiteAttack;
        long blackCramped = BLACK_HOME_HALF & whiteAttack & ~blackAttack;
        int crampDelta = Long.bitCount(blackCramped) - Long.bitCount(whiteCramped);

        long whitePawnAttacks = computeWhitePawnAttacks(context.getWhitePawns());
        long blackPawnAttacks = computeBlackPawnAttacks(context.getBlackPawns());

        long whiteOutpostCandidates = (context.getWhiteKnights() | context.getWhiteBishops())
                & WHITE_OUTPOST_MASK;
        long blackOutpostCandidates = (context.getBlackKnights() | context.getBlackBishops())
                & BLACK_OUTPOST_MASK;

        int whiteOutposts = countOutposts(whiteOutpostCandidates, whitePawnAttacks, blackPawnAttacks);
        int blackOutposts = countOutposts(blackOutpostCandidates, blackPawnAttacks, whitePawnAttacks);
        int outpostDelta = whiteOutposts - blackOutposts;

        midgameScore = safeDelta * Tuning.spaceSafeSquareMidgame()
                + crampDelta * Tuning.spaceCrampMidgame()
                + outpostDelta * Tuning.spaceOutpostMidgame();
        endgameScore = safeDelta * Tuning.spaceSafeSquareEndgame()
                + crampDelta * Tuning.spaceCrampEndgame()
                + outpostDelta * Tuning.spaceOutpostEndgame();

        dirty = false;
    }

    private boolean ensureInitialized(MoveContext moveContext) {
        if (initialized) {
            return true;
        }
        EvaluationContext context = moveContext.getCurrentContext();
        if (context == null) {
            return false;
        }
        initialize(context);
        return true;
    }

    private static int countOutposts(long candidates, long friendlyPawnAttacks, long enemyPawnAttacks) {
        long supported = candidates & friendlyPawnAttacks;
        long safeFromEnemyPawns = supported & ~enemyPawnAttacks;
        return Long.bitCount(safeFromEnemyPawns);
    }

    private static long computeWhitePawnAttacks(long pawns) {
        long west = (pawns << 7) & ~FILE_H;
        long east = (pawns << 9) & ~FILE_A;
        return west | east;
    }

    private static long computeBlackPawnAttacks(long pawns) {
        long west = (pawns >>> 7) & ~FILE_A;
        long east = (pawns >>> 9) & ~FILE_H;
        return west | east;
    }
}
