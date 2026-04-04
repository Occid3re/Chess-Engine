package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.MoveOrderingParameters;
import julius.game.chessengine.utils.Score;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

/**
 * Move ordering logic extracted from the AI class. Responsible for scoring,
 * bucketing, and sorting moves to maximise alpha-beta cut-off efficiency.
 */
final class MoveOrderer {

    enum MoveBucket {
        TT,
        PROMOTION,
        CAPTURE_GOOD,
        CAPTURE_EQUAL,
        KILLER0,
        KILLER1,
        QUIET,
        CAPTURE_BAD
    }

    static final int MAX_MOVE_LIST_SIZE = 218;

    private final MoveOrderingParameters.Snapshot parameters;
    private final MoveBucket[] bucketOrder;
    private final ThreadLocal<SortBuffers> sortBuffers;
    private final ThreadLocal<Map<Integer, Integer>> seeCacheThreadLocal;

    MoveOrderer(MoveOrderingParameters.Snapshot parameters) {
        this.parameters = parameters;
        this.bucketOrder = buildMoveBucketOrder(parameters);
        this.sortBuffers = ThreadLocal.withInitial(
                () -> new SortBuffers(MAX_MOVE_LIST_SIZE, MoveBucket.values().length));
        this.seeCacheThreadLocal = ThreadLocal.withInitial(() -> new java.util.HashMap<>(64));
    }

    MoveBucket[] getBucketOrder() {
        return bucketOrder;
    }

    ThreadLocal<SortBuffers> getSortBuffersRef() {
        return sortBuffers;
    }

    ThreadLocal<Map<Integer, Integer>> getSeeCacheRef() {
        return seeCacheThreadLocal;
    }

    /**
     * Orders moves using TT hints, promotions, SEE-aware capture sorting,
     * killer moves and history heuristics. The method modifies the input
     * list in place and returns it.
     */
    IntArrayList sortMovesByEfficiency(IntArrayList moves, int currentDepth, long boardHash,
                                       int prevMove, Engine simulatorEngine,
                                       Heuristics heuristics,
                                       TranspositionTable<TranspositionTableEntry> transpositionTable) {
        final int size = moves.size();
        final Map<Integer, Integer> seeCache = seeCacheThreadLocal.get();
        seeCache.clear();

        if (size == 0) {
            return moves;
        }

        final SortBuffers buffers = sortBuffers.get();
        final int[] moveBuffer = buffers.moveBuffer;
        final int[] scoreBuffer = buffers.scoreBuffer;
        final int[] orderedBuffer = buffers.orderedBuffer;
        final IntArrayList[] bucketIndexes = buffers.bucketIndexes;
        for (IntArrayList bucket : bucketIndexes) {
            bucket.clear();
        }

        final IntArrayList ttBucket = bucketIndexes[MoveBucket.TT.ordinal()];
        final IntArrayList promotionBucket = bucketIndexes[MoveBucket.PROMOTION.ordinal()];
        final IntArrayList captureGoodBucket = bucketIndexes[MoveBucket.CAPTURE_GOOD.ordinal()];
        final IntArrayList captureEqualBucket = bucketIndexes[MoveBucket.CAPTURE_EQUAL.ordinal()];
        final IntArrayList killer0Bucket = bucketIndexes[MoveBucket.KILLER0.ordinal()];
        final IntArrayList killer1Bucket = bucketIndexes[MoveBucket.KILLER1.ordinal()];
        final IntArrayList quietBucket = bucketIndexes[MoveBucket.QUIET.ordinal()];
        final IntArrayList captureBadBucket = bucketIndexes[MoveBucket.CAPTURE_BAD.ordinal()];

        final int[][] killerMoves = heuristics.getKillers();
        final int[][] historyTable = heuristics.getHistory();
        final int[][] counterMove = heuristics.getCounter();

        final int depthIndex = Math.max(0, Math.min(currentDepth, killerMoves.length - 1));

        final int promotionBonus = parameters.promotionBonus();
        final int killer0Bonus = parameters.killer0Bonus();
        final int killer1Bonus = parameters.killer1Bonus();
        final int killerMoveScore = parameters.killerMoveScore();
        final int captureMvvMultiplier = parameters.captureMvvMultiplier();
        final int captureSeeMultiplier = parameters.captureSeeMultiplier();
        final int promotionSeeMultiplier = parameters.promotionSeeMultiplier();
        final int castlingBonus = parameters.castlingBonus();
        final int captureSeeClamp = Math.max(0, parameters.captureSeeClamp());
        final int promotionSeeClamp = Math.max(0, parameters.promotionSeeClamp());
        final int maxScore = Math.max(1, parameters.maxScore());

        // Hash move (TT)
        TranspositionTableEntry ttEntry = transpositionTable.get(boardHash);
        final int ttMove = ttEntry != null ? ttEntry.bestMove : -1;

        // Pre-fetch killers for this depth
        final int k0 = killerMoves[depthIndex][0];
        final int k1 = killerMoves[depthIndex][1];

        final int prevFrom = (prevMove >= 0) ? (prevMove & 0x3F) : -1;
        final int prevTo = (prevMove >= 0) ? ((prevMove >>> 6) & 0x3F) : -1;
        final int cm = (prevFrom >= 0) ? counterMove[prevFrom][prevTo] : -1;
        final int counterMoveBonus = parameters.counterMoveBonus();

        for (int i = 0; i < size; i++) {
            final int moveInt = moves.getInt(i);

            final boolean isCapture = MoveHelper.isCapture(moveInt);
            final boolean isPromotion = MoveHelper.isPawnPromotionMove(moveInt);

            int seeValue = 0;
            boolean hasSee = false;
            if (isCapture) {
                seeValue = seeCache.computeIfAbsent(moveInt, simulatorEngine::see);
                hasSee = true;
            }

            int score;
            IntArrayList targetBucket;

            if (moveInt == ttMove) {
                score = maxScore;
                targetBucket = ttBucket;
            } else if (isPromotion) {
                int base = calculateMvvLvaScore(moveInt);
                int seeBonus = 0;
                if (hasSee) {
                    int cappedSee = promotionSeeClamp > 0
                            ? Math.max(-promotionSeeClamp, Math.min(promotionSeeClamp, seeValue))
                            : seeValue;
                    seeBonus = cappedSee * promotionSeeMultiplier;
                }
                score = base + promotionBonus + seeBonus;
                targetBucket = promotionBucket;
            } else if (isCapture) {
                final int mvvLva = calculateMvvLvaScore(moveInt);
                int cappedSee = captureSeeClamp > 0
                        ? Math.max(-captureSeeClamp, Math.min(captureSeeClamp, seeValue))
                        : seeValue;
                score = (mvvLva * captureMvvMultiplier) + (cappedSee * captureSeeMultiplier);
                if (score < 0) score = 0;
                if (seeValue > 0) {
                    targetBucket = captureGoodBucket;
                } else if (seeValue == 0) {
                    targetBucket = captureEqualBucket;
                } else {
                    targetBucket = captureBadBucket;
                }
            } else if (moveInt == k0) {
                score = killerMoveScore + killer0Bonus;
                targetBucket = killer0Bucket;
            } else if (moveInt == k1) {
                score = killerMoveScore + killer1Bonus;
                targetBucket = killer1Bucket;
            } else {
                final int from = moveInt & 0x3F;
                final int to = (moveInt >>> 6) & 0x3F;
                score = historyTable[from][to];
                if (moveInt == cm) score += counterMoveBonus;
                if (MoveHelper.isCastlingMove(moveInt)) {
                    score += castlingBonus;
                }
                targetBucket = quietBucket;
            }

            moveBuffer[i] = moveInt;
            int s = score;
            if (s < 0) {
                s = 0;
            } else if (s > maxScore) {
                s = maxScore;
            }
            scoreBuffer[i] = s;
            insertByScore(targetBucket, i, scoreBuffer, moveBuffer);
        }

        int outIndex = 0;
        for (MoveBucket bucket : bucketOrder) {
            outIndex = writeBucket(bucketIndexes[bucket.ordinal()], moveBuffer, orderedBuffer, outIndex);
        }

        MoveContainerUtils.overwriteFromBuffer(moves, orderedBuffer, size);
        return moves;
    }

    // ---- Static utility methods ----

    static MoveBucket[] buildMoveBucketOrder(MoveOrderingParameters.Snapshot parameters) {
        MoveBucket[] order = MoveBucket.values().clone();
        Arrays.sort(order, Comparator
                .comparingInt((MoveBucket bucket) -> resolveCategoryWeight(bucket, parameters))
                .reversed()
                .thenComparingInt(MoveBucket::ordinal));
        return order;
    }

    static int resolveCategoryWeight(MoveBucket bucket, MoveOrderingParameters.Snapshot parameters) {
        return switch (bucket) {
            case TT -> parameters.categoryTt();
            case PROMOTION -> parameters.categoryPromotion();
            case CAPTURE_GOOD -> parameters.categoryCaptureGood();
            case CAPTURE_EQUAL -> parameters.categoryCaptureEqual();
            case KILLER0 -> parameters.categoryKiller0();
            case KILLER1 -> parameters.categoryKiller1();
            case QUIET -> parameters.categoryQuiet();
            case CAPTURE_BAD -> parameters.categoryCaptureBad();
        };
    }

    static void insertByScore(IntArrayList bucket, int moveIndex, int[] scoreBuffer, int[] moveBuffer) {
        int score = scoreBuffer[moveIndex];
        int move = moveBuffer[moveIndex];
        int insertPosition = bucket.size();
        while (insertPosition > 0) {
            int existingIndex = bucket.getInt(insertPosition - 1);
            int existingScore = scoreBuffer[existingIndex];
            if (score > existingScore) {
                insertPosition--;
                continue;
            }
            if (score == existingScore && move > moveBuffer[existingIndex]) {
                insertPosition--;
                continue;
            }
            break;
        }
        bucket.add(insertPosition, moveIndex);
    }

    static int writeBucket(IntArrayList bucket, int[] sourceMoves, int[] target, int startIndex) {
        for (int i = 0, size = bucket.size(); i < size; i++) {
            target[startIndex++] = sourceMoves[bucket.getInt(i)];
        }
        return startIndex;
    }

    static int calculateMvvLvaScore(int move) {
        if (!MoveHelper.isCapture(move)) {
            return 0;
        }
        int victimValue = Score.getPieceValue(MoveHelper.deriveCapturedPieceTypeBits(move));
        int attackerValue = Score.getPieceValue(MoveHelper.derivePieceTypeBits(move));
        return victimValue - attackerValue;
    }
}
