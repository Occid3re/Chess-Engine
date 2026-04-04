package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.tuning.MoveOrderingParameters;
import julius.game.chessengine.utils.Score;

import java.util.Arrays;
import java.util.Comparator;

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

    private MoveOrderer() {
    }

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
