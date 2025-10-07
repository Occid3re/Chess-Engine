package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.helper.PawnHelper;
import julius.game.chessengine.tuning.Tuning;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.BitHelper.RankMasks;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_PUSHES;

/**
 * Incrementally evaluates pawn structure terms and exposes tapered (midgame/endgame)
 * components so the evaluation pipeline and {@link julius.game.chessengine.utils.Score}
 * can blend them consistently.
 */
public final class PawnStructureModule implements EvaluationModule, MaterialModule.PawnChangeListener {
    private static final int WHITE = 0;
    private static final int BLACK = 1;
    private static final long WHITE_ADVANCED_RANKS = RankMasks[3] | RankMasks[4] | RankMasks[5] | RankMasks[6];
    private static final long BLACK_ADVANCED_RANKS = RankMasks[4] | RankMasks[3] | RankMasks[2] | RankMasks[1];

    private static final long[] ADJACENT_FILE_MASKS = new long[64];
    private static final long[] FORWARD_RANK_MASKS_WHITE = new long[64];
    private static final long[] FORWARD_RANK_MASKS_BLACK = new long[64];
    private static final int[] PASSED_RANK_WEIGHT_WHITE = new int[64];
    private static final int[] PASSED_RANK_WEIGHT_BLACK = new int[64];
    private static final int[] DISTANCE_TO_PROMOTION_WHITE = new int[64];
    private static final int[] DISTANCE_TO_PROMOTION_BLACK = new int[64];
    private static final int[] ADVANCED_RANK_WEIGHT_WHITE = new int[64];
    private static final int[] ADVANCED_RANK_WEIGHT_BLACK = new int[64];

    static {
        for (int square = 0; square < 64; square++) {
            int file = square & 7;
            long adjacent = FileMasks[file];
            if (file > 0) {
                adjacent |= FileMasks[file - 1];
            }
            if (file < 7) {
                adjacent |= FileMasks[file + 1];
            }
            ADJACENT_FILE_MASKS[square] = adjacent;

            int rankZero = square >>> 3;
            int rankIndex = rankZero + 1;

            long forwardWhite = 0L;
            for (int r = rankZero + 1; r < 8; r++) {
                forwardWhite |= RankMasks[r];
            }
            long forwardBlack = 0L;
            for (int r = rankZero - 1; r >= 0; r--) {
                forwardBlack |= RankMasks[r];
            }
            FORWARD_RANK_MASKS_WHITE[square] = forwardWhite;
            FORWARD_RANK_MASKS_BLACK[square] = forwardBlack;

            PASSED_RANK_WEIGHT_WHITE[square] = rankIndex - 1;
            PASSED_RANK_WEIGHT_BLACK[square] = 8 - rankIndex;
            DISTANCE_TO_PROMOTION_WHITE[square] = 8 - rankIndex;
            DISTANCE_TO_PROMOTION_BLACK[square] = rankIndex - 1;
            ADVANCED_RANK_WEIGHT_WHITE[square] = Math.max(0, rankIndex - 3);
            ADVANCED_RANK_WEIGHT_BLACK[square] = Math.max(0, 6 - rankIndex);
        }
    }

    private final ConcurrentMap<PawnStructureKey, CachedStructure> structureCache = new ConcurrentHashMap<>();

    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;

    private final int centerPawnBonus;
    private final int passedPawnBonus;
    private final int connectedPawnBonus;
    private final int pawnIslandPenalty;
    private final int doubledPawnPenalty;
    private final int isolatedPawnPenalty;
    private final int advancedPawnBonus;
    private final int blockedPawnPenalty;
    private final int backwardPawnPenalty;
    private final int ownKingBlocksPassedPawnPenalty;
    private final int passedPawnFreePathBonusPerRank;
    private final int rookHalfOpenFileBonus;
    private final int rookOpenFileBonus;

    public PawnStructureModule() {
        this.centerPawnBonus = Tuning.centerPawnBonus();
        this.passedPawnBonus = Tuning.passedPawnBonus();
        this.connectedPawnBonus = Tuning.connectedPawnBonus();
        this.pawnIslandPenalty = Tuning.pawnIslandPenalty();
        this.doubledPawnPenalty = Tuning.doubledPawnPenalty();
        this.isolatedPawnPenalty = Tuning.isolatedPawnPenalty();
        this.advancedPawnBonus = Tuning.advancedPawnBonus();
        this.blockedPawnPenalty = Tuning.blockedPawnPenalty();
        this.backwardPawnPenalty = Tuning.backwardPawnPenalty();
        this.ownKingBlocksPassedPawnPenalty = Tuning.ownKingBlocksPassedPawnPenalty();
        this.passedPawnFreePathBonusPerRank = Tuning.passedPawnFreePathBonusPerRank();
        this.rookHalfOpenFileBonus = Tuning.rookHalfOpenFileBonus();
        this.rookOpenFileBonus = Tuning.rookOpenFileBonus();
    }

    @Override
    public void initialize(EvaluationContext context) {
        evaluateContext(Objects.requireNonNull(context, "context"));
    }

    @Override
    public void evaluate(EvaluationContext context) {
        Objects.requireNonNull(context, "context");
        if (!dirty) {
            return;
        }
        evaluateContext(context);
    }

    private void evaluateContext(EvaluationContext context) {
        ImmutableBoardView board = context.getBoardView();
        if (board == null) {
            reset();
            return;
        }

        long whitePawns = board.getWhitePawns();
        long blackPawns = board.getBlackPawns();
        long allPawns = whitePawns | blackPawns;
        long allPieces = board.getAllPieces();
        long whiteRooks = board.getWhiteRooks();
        long blackRooks = board.getBlackRooks();
        long whiteKing = board.getWhiteKing();
        long blackKing = board.getBlackKing();
        long whiteAttacks = context.getWhiteAttackMap();
        long blackAttacks = context.getBlackAttackMap();

        CachedStructure structure = getStructure(whitePawns, blackPawns);

        int whiteHalfOpen = countHalfOpenFilesWithRooks(whiteRooks, whitePawns, blackPawns) * rookHalfOpenFileBonus;
        int blackHalfOpen = countHalfOpenFilesWithRooks(blackRooks, blackPawns, whitePawns) * rookHalfOpenFileBonus;
        int whiteOpenFiles = countOpenFilesWithRooks(whiteRooks, allPawns) * rookOpenFileBonus;
        int blackOpenFiles = countOpenFilesWithRooks(blackRooks, allPawns) * rookOpenFileBonus;

        int whitePassed = calculatePassedPawnBonus(whitePawns, blackPawns, allPieces, whiteKing, true);
        int blackPassed = calculatePassedPawnBonus(blackPawns, whitePawns, allPieces, blackKing, false);

        int whiteAdvanceBase = calculatePawnAdvanceBonus(whitePawns, allPieces, blackAttacks, true);
        int blackAdvanceBase = calculatePawnAdvanceBonus(blackPawns, allPieces, whiteAttacks, false);

        int whiteBlockedBase = calculateBlockedPawnPenalty(whitePawns, allPieces, true);
        int blackBlockedBase = calculateBlockedPawnPenalty(blackPawns, allPieces, false);

        int whiteBackwardBase = calculateBackwardPawnPenalty(whitePawns, blackPawns, allPieces, true, backwardPawnPenalty);
        int blackBackwardBase = calculateBackwardPawnPenalty(blackPawns, whitePawns, allPieces, false, backwardPawnPenalty);

        int whiteMidgameTotal = structure.whiteCenterPawnBonus
                + structure.whiteDoubledPawnPenalty
                + structure.whiteIsolatedPawnPenalty
                + structure.whiteConnectedPawnBonus
                + whiteHalfOpen
                + whiteOpenFiles
                + structure.whitePawnIslandPenalty
                + whitePassed
                + whiteAdvanceBase
                + whiteBlockedBase
                + whiteBackwardBase;

        int whiteEndgameTotal = structure.whiteCenterPawnBonus
                + structure.whiteDoubledPawnPenalty
                + structure.whiteIsolatedPawnPenalty
                + structure.whiteConnectedPawnBonus
                + whiteHalfOpen
                + whiteOpenFiles
                + structure.whitePawnIslandPenalty
                + whitePassed
                + scaleEndgame(whiteAdvanceBase)
                + scaleEndgame(whiteBlockedBase)
                + scaleEndgame(whiteBackwardBase);

        int blackMidgameTotal = structure.blackCenterPawnBonus
                + structure.blackDoubledPawnPenalty
                + structure.blackIsolatedPawnPenalty
                + structure.blackConnectedPawnBonus
                + blackHalfOpen
                + blackOpenFiles
                + structure.blackPawnIslandPenalty
                + blackPassed
                + blackAdvanceBase
                + blackBlockedBase
                + blackBackwardBase;

        int blackEndgameTotal = structure.blackCenterPawnBonus
                + structure.blackDoubledPawnPenalty
                + structure.blackIsolatedPawnPenalty
                + structure.blackConnectedPawnBonus
                + blackHalfOpen
                + blackOpenFiles
                + structure.blackPawnIslandPenalty
                + blackPassed
                + scaleEndgame(blackAdvanceBase)
                + scaleEndgame(blackBlockedBase)
                + scaleEndgame(blackBackwardBase);

        midgameScoreCache = whiteMidgameTotal - blackMidgameTotal;
        endgameScoreCache = whiteEndgameTotal - blackEndgameTotal;
        dirty = false;
    }

    private void reset() {
        midgameScoreCache = 0;
        endgameScoreCache = 0;
        dirty = false;
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        handleMove(moveContext.getMove());
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        handleMove(moveContext.getMove());
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

    @Override
    public void onPawnAdded(boolean isWhite, int squareIndex) {
        dirty = true;
    }

    @Override
    public void onPawnRemoved(boolean isWhite, int squareIndex) {
        dirty = true;
    }

    private void handleMove(int move) {
        dirty = true;
    }

    private CachedStructure getStructure(long whitePawns, long blackPawns) {
        PawnStructureKey key = new PawnStructureKey(whitePawns, blackPawns);
        return structureCache.computeIfAbsent(key, k -> new CachedStructure(
                whitePawns,
                blackPawns,
                centerPawnBonus,
                doubledPawnPenalty,
                isolatedPawnPenalty,
                connectedPawnBonus,
                pawnIslandPenalty));
    }

    private int calculatePawnAdvanceBonus(long pawns, long allPieces, long enemyAttacks, boolean isWhite) {
        long advancedPawns = pawns & (isWhite ? WHITE_ADVANCED_RANKS : BLACK_ADVANCED_RANKS);
        if (advancedPawns == 0) {
            return 0;
        }
        int bonus = 0;
        int color = isWhite ? WHITE : BLACK;
        int[] rankWeights = isWhite ? ADVANCED_RANK_WEIGHT_WHITE : ADVANCED_RANK_WEIGHT_BLACK;
        while (advancedPawns != 0) {
            int square = Long.numberOfTrailingZeros(advancedPawns);
            advancedPawns &= advancedPawns - 1;
            long forward = PAWN_PUSHES[color][square];
            if (forward == 0L || (forward & (allPieces | enemyAttacks)) != 0) {
                continue;
            }
            bonus += advancedPawnBonus * rankWeights[square];
        }
        return bonus;
    }

    private int calculateBlockedPawnPenalty(long pawns, long allPieces, boolean isWhite) {
        if (pawns == 0) {
            return 0;
        }
        int penalty = 0;
        int color = isWhite ? WHITE : BLACK;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            long forward = PAWN_PUSHES[color][square];
            if (forward != 0L && (forward & allPieces) != 0) {
                penalty += blockedPawnPenalty;
            }
        }
        return penalty;
    }

    private static int calculateBackwardPawnPenalty(long pawns, long enemyPawns, long allPieces, boolean isWhite,
            int backwardPenalty) {
        if (pawns == 0 || backwardPenalty == 0) {
            return 0;
        }
        int penalty = 0;
        int color = isWhite ? WHITE : BLACK;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            long forward = PAWN_PUSHES[color][square];
            if (forward == 0L || (forward & allPieces) != 0) {
                continue;
            }
            int forwardIndex = Long.numberOfTrailingZeros(forward);
            if (forwardIndex >= 64) {
                continue;
            }
            long enemyAttack = PAWN_ATTACKS[color][forwardIndex] & enemyPawns;
            if (enemyAttack != 0) {
                penalty += backwardPenalty;
            }
        }
        return penalty;
    }

    private int calculatePassedPawnBonus(long pawns, long opponentPawns, long allPieces, long ownKing, boolean isWhite) {
        if (pawns == 0) {
            return 0;
        }
        int bonus = 0;
        long[] forwardMasks = isWhite ? FORWARD_RANK_MASKS_WHITE : FORWARD_RANK_MASKS_BLACK;
        int[] rankWeights = isWhite ? PASSED_RANK_WEIGHT_WHITE : PASSED_RANK_WEIGHT_BLACK;
        int[] distances = isWhite ? DISTANCE_TO_PROMOTION_WHITE : DISTANCE_TO_PROMOTION_BLACK;
        int color = isWhite ? WHITE : BLACK;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;

            long forwardRanks = forwardMasks[square];
            if ((opponentPawns & (ADJACENT_FILE_MASKS[square] & forwardRanks)) != 0) {
                continue;
            }

            int base = passedPawnBonus * rankWeights[square];
            long filePathAhead = forwardRanks & FileMasks[square & 7];
            long oneStep = PAWN_PUSHES[color][square];

            if ((oneStep & ownKing) != 0) {
                base += ownKingBlocksPassedPawnPenalty;
            }
            if (filePathAhead != 0 && (filePathAhead & allPieces) == 0) {
                base += passedPawnFreePathBonusPerRank * distances[square];
            }

            bonus += base;
        }
        return bonus;
    }

    private int countHalfOpenFilesWithRooks(long rooksBitboard, long friendlyPawns, long enemyPawns) {
        if (rooksBitboard == 0L) {
            return 0;
        }
        int count = 0;
        for (int file = 0; file < 8; file++) {
            long fileMask = FileMasks[file];
            if ((rooksBitboard & fileMask) == 0) {
                continue;
            }
            if ((friendlyPawns & fileMask) == 0 && (enemyPawns & fileMask) != 0) {
                count++;
            }
        }
        return count;
    }

    private int countOpenFilesWithRooks(long rooksBitboard, long allPawns) {
        if (rooksBitboard == 0L) {
            return 0;
        }
        int count = 0;
        for (int file = 0; file < 8; file++) {
            long fileMask = FileMasks[file];
            if ((rooksBitboard & fileMask) != 0 && (allPawns & fileMask) == 0) {
                count++;
            }
        }
        return count;
    }

    private static int scaleEndgame(int value) {
        return value << 1;
    }

    private record PawnStructureKey(long white, long black) { }

    private static final class CachedStructure {
        private final int whiteCenterPawnBonus;
        private final int blackCenterPawnBonus;
        private final int whiteDoubledPawnPenalty;
        private final int blackDoubledPawnPenalty;
        private final int whiteIsolatedPawnPenalty;
        private final int blackIsolatedPawnPenalty;
        private final int whiteConnectedPawnBonus;
        private final int blackConnectedPawnBonus;
        private final int whitePawnIslandPenalty;
        private final int blackPawnIslandPenalty;

        private CachedStructure(long whitePawns,
                                long blackPawns,
                                int centerBonus,
                                int doubledPenalty,
                                int isolatedPenalty,
                                int connectedBonus,
                                int islandPenalty) {
            this.whiteCenterPawnBonus = PawnHelper.countCenterPawns(whitePawns) * centerBonus;
            this.blackCenterPawnBonus = PawnHelper.countCenterPawns(blackPawns) * centerBonus;
            this.whiteDoubledPawnPenalty = PawnHelper.countDoubledPawns(whitePawns) * doubledPenalty;
            this.blackDoubledPawnPenalty = PawnHelper.countDoubledPawns(blackPawns) * doubledPenalty;
            this.whiteIsolatedPawnPenalty = PawnHelper.countIsolatedPawns(whitePawns) * isolatedPenalty;
            this.blackIsolatedPawnPenalty = PawnHelper.countIsolatedPawns(blackPawns) * isolatedPenalty;
            this.whiteConnectedPawnBonus = PawnHelper.countConnectedPawns(whitePawns) * connectedBonus;
            this.blackConnectedPawnBonus = PawnHelper.countConnectedPawns(blackPawns) * connectedBonus;
            this.whitePawnIslandPenalty = Math.max(0, PawnHelper.countPawnIslands(whitePawns) - 1) * islandPenalty;
            this.blackPawnIslandPenalty = Math.max(0, PawnHelper.countPawnIslands(blackPawns) - 1) * islandPenalty;
        }
    }
}
