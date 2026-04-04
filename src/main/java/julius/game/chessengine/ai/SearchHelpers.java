package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.NullMoveParameters;
import julius.game.chessengine.tuning.SearchPruningParameters;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;

/**
 * Static helper methods for the alpha-beta search tree: board queries,
 * null-move reduction, LMR, futility margins, and tactical detection.
 * Extracted from AI to keep the search loop focused on control flow.
 */
final class SearchHelpers {

    private SearchHelpers() {
    }

    // ---- Board query helpers ----

    static boolean attacksOpponentQueenNow(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyQueen = moverIsWhite ? bb.getBlackQueens() : bb.getWhiteQueens();
        if (enemyQueen == 0) return false;
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & enemyQueen) != 0L;
    }

    static boolean attacksOpponentKingZone(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyKing = moverIsWhite ? bb.getBlackKing() : bb.getWhiteKing();
        if (enemyKing == 0L) {
            return false;
        }
        int kingIndex = Long.numberOfTrailingZeros(enemyKing);
        long kingZone = KING_ATTACKS[kingIndex];
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & kingZone) != 0L;
    }

    static int countPawnsOnFile(BitBoard board, long fileMask) {
        if (fileMask == 0L) {
            return 0;
        }
        long pawns = (board.getWhitePawns() | board.getBlackPawns()) & fileMask;
        return Long.bitCount(pawns);
    }

    static boolean openedFileTowardKing(BitBoard boardAfterMove, long kingFileMask,
                                        int pawnsBefore, boolean interactsWithKingFile) {
        if (!interactsWithKingFile || kingFileMask == 0L || pawnsBefore <= 0) {
            return false;
        }
        int pawnsAfter = countPawnsOnFile(boardAfterMove, kingFileMask);
        return pawnsAfter < pawnsBefore;
    }

    static boolean isZeroingMove(int move) {
        if (move < 0) {
            return false;
        }
        if (MoveHelper.isCapture(move)) {
            return true;
        }
        int pieceBits = MoveHelper.derivePieceTypeBits(move);
        return pieceBits == 1;
    }

    // ---- Null-move reduction ----

    static int computeNullMoveReduction(BitBoard board, int depth, boolean isWhite, int mobility,
                                        NullMoveParameters.Snapshot nullMoveParameters) {
        int maxReduction = depth - 2;
        if (maxReduction <= 0) {
            return 0;
        }

        long pieces = isWhite ? board.getWhitePieces() : board.getBlackPieces();
        long pawns = isWhite ? board.getWhitePawns() : board.getBlackPawns();
        int nonPawnMaterial = Long.bitCount(pieces) - Long.bitCount(pawns);
        if (nonPawnMaterial < 0) {
            nonPawnMaterial = 0;
        }

        double reductionEstimate = getReductionEstimate(depth, mobility, nonPawnMaterial, nullMoveParameters);

        int reduction = (int) Math.floor(Math.max(0.0, reductionEstimate));
        return Math.min(reduction, maxReduction);
    }

    static double getReductionEstimate(int depth, int mobility, int nonPawnMaterial,
                                       NullMoveParameters.Snapshot params) {
        int depthCap = Math.max(1, params.depthCap());
        int materialCap = Math.max(1, params.materialCap());
        int mobilityCap = Math.max(1, params.mobilityCap());

        double depthFactor = Math.min(Math.max(depth, 0), depthCap) / (double) depthCap;
        double materialFactor = Math.min(Math.max(nonPawnMaterial, 0), materialCap) / (double) materialCap;
        double mobilityFactor = Math.min(Math.max(mobility, 0), mobilityCap) / (double) mobilityCap;

        double reductionEstimate = params.baseReduction()
                + (depthFactor * params.depthWeight())
                + (materialFactor * params.materialWeight())
                + (mobilityFactor * params.mobilityWeight());

        if (nonPawnMaterial <= params.lowMaterialThreshold()
                || mobility <= params.lowMobilityThreshold()) {
            reductionEstimate -= params.lowMaterialPenalty();
        }
        if (mobility <= params.veryLowMobilityThreshold()) {
            reductionEstimate -= params.veryLowMobilityPenalty();
        }
        return reductionEstimate;
    }

    // ---- Late-move reduction ----

    static int lmrReduction(int depth, int moveIndex, int historyScore,
                            int[][][] lmrReductionTable, int lmrBucketCount,
                            SearchPruningParameters.Snapshot pruning) {
        if (depth <= 1) {
            return 0;
        }

        int clampedDepth = Math.min(depth, lmrReductionTable.length - 1);
        int clampedMoveIndex = Math.max(0, Math.min(moveIndex,
                lmrReductionTable.length > 0 && lmrReductionTable[0].length > 0
                        ? lmrReductionTable[0].length - 1 : 0));

        int historyReductionMax = Math.max(0, pruning.historyReductionMax());
        int history = Math.max(0, Math.min(historyScore, historyReductionMax));
        double normalized = historyReductionMax == 0
                ? 0.0
                : history / (double) historyReductionMax;
        int buckets = Math.max(1, lmrBucketCount);
        double bucketPosition = buckets == 1 ? 0.0 : normalized * (buckets - 1);
        int lowerBucket = Math.max(0, (int) Math.floor(bucketPosition));
        int upperBucket = Math.min(buckets - 1, lowerBucket + 1);
        double fraction = bucketPosition - lowerBucket;

        int lowerValue = lmrReductionTable[clampedDepth][clampedMoveIndex][lowerBucket];
        if (upperBucket == lowerBucket) {
            return Math.min(lowerValue, depth - 1);
        }

        int upperValue = lmrReductionTable[clampedDepth][clampedMoveIndex][upperBucket];
        double interpolated = lowerValue + fraction * (upperValue - lowerValue);
        int reduction = (int) Math.floor(interpolated + 1e-9);
        int maxReduction = Math.max(0, depth - 1);
        if (reduction < 0) {
            reduction = 0;
        }
        if (reduction > maxReduction) {
            reduction = maxReduction;
        }
        return reduction;
    }

    // ---- Futility pruning ----

    static int futilityMarginForRemainingDepth(int remainingDepth,
                                               SearchPruningParameters.Snapshot pruning) {
        if (remainingDepth <= 1) {
            return pruning.fpMarginDepth1();
        }
        if (remainingDepth == 2) {
            return pruning.fpMarginDepth2();
        }
        return 0;
    }
}
