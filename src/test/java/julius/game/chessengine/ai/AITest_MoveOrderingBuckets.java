package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.tuning.Tuning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.TestLoggingExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TestLoggingExtension.class)
class AITest_MoveOrderingBuckets {

    private static final String MOVE_ORDERING_FEN = "4k3/8/8/8/4B3/3r4/8/4K3 w - - 0 1";

    @Test
    @DisplayName("Move bucket priority follows category weights from the snapshot")
    void moveBucketPriorityFollowsSnapshotCategories() throws Exception {
        Engine baselineEngine = new Engine();
        baselineEngine.importBoardFromFen(MOVE_ORDERING_FEN);

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .build();

        AI baselineAi = new AI(baselineEngine, tuning);

        IntArrayList legalMoves = baselineEngine.getAllLegalMoves();
        assertFalse(legalMoves.isEmpty(), "Position should expose moves to order");

        int captureMove = findFirst(legalMoves, MoveHelper::isCapture);
        if (captureMove == -1) {
            fail("Expected at least one capture move");
        }

        assertTrue(baselineEngine.see(captureMove) > 0, "Capture must be SEE-positive to land in the good bucket");

        int quietMove = findFirst(legalMoves, m -> !MoveHelper.isCapture(m) && !MoveHelper.isPawnPromotionMove(m));
        if (quietMove == -1) {
            fail("Expected at least one quiet move");
        }

        IntArrayList baselineOrdered = new IntArrayList(legalMoves);
        baselineAi.sortMovesByEfficiency(baselineOrdered, 0, baselineEngine.getBoardStateHash(), -1, baselineEngine);

        int baselineCaptureIndex = indexOf(baselineOrdered, captureMove);
        int baselineQuietIndex = indexOf(baselineOrdered, quietMove);

        assertTrue(baselineCaptureIndex >= 0, "Capture must be present after ordering");
        assertTrue(baselineQuietIndex >= 0, "Quiet move must be present after ordering");
        assertTrue(baselineCaptureIndex < baselineQuietIndex,
                () -> "Default ordering should prioritise capture bucket before quiet: "
                        + describeMoveOrder(baselineOrdered));

        Field quietField = Tuning.class.getDeclaredField("moveOrderingCategoryQuiet");
        Field captureGoodField = Tuning.class.getDeclaredField("moveOrderingCategoryCaptureGood");

        quietField.setAccessible(true);
        captureGoodField.setAccessible(true);

        int originalQuiet = quietField.getInt(null);
        int originalCaptureGood = captureGoodField.getInt(null);

        try {
            quietField.setInt(null, originalCaptureGood + 100);
            captureGoodField.setInt(null, originalQuiet);

            Engine adjustedEngine = new Engine();
            adjustedEngine.importBoardFromFen(MOVE_ORDERING_FEN);

            AI adjustedAi = new AI(adjustedEngine, tuning);

            IntArrayList adjustedOrdered = adjustedEngine.getAllLegalMoves();
            adjustedAi.sortMovesByEfficiency(adjustedOrdered, 0, adjustedEngine.getBoardStateHash(), -1, adjustedEngine);

            int adjustedCaptureIndex = indexOf(adjustedOrdered, captureMove);
            int adjustedQuietIndex = indexOf(adjustedOrdered, quietMove);

            assertTrue(adjustedCaptureIndex >= 0, "Capture must be present after adjusted ordering");
            assertTrue(adjustedQuietIndex >= 0, "Quiet move must be present after adjusted ordering");

            assertTrue(adjustedQuietIndex < adjustedCaptureIndex,
                    () -> "Quiet bucket should outrank capture bucket after tweaking categories: "
                            + describeMoveOrder(adjustedOrdered));
        } finally {
            quietField.setInt(null, originalQuiet);
            captureGoodField.setInt(null, originalCaptureGood);
        }
    }

    @Test
    @DisplayName("Buckets are sorted by score (desc) with move tie-breaks and feed writeBucket as-is")
    void bucketsMaintainScoreAndMoveOrdering() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .build();

        AI ai = new AI(engine, tuning);

        IntArrayList ordered = engine.getAllLegalMoves();
        assertFalse(ordered.isEmpty(), "Position should expose moves to order");

        ai.sortMovesByEfficiency(ordered, 0, engine.getBoardStateHash(), -1, engine);

        Field sortBuffersField = AI.class.getDeclaredField("sortBuffers");
        sortBuffersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<SortBuffers> sortBuffers = (ThreadLocal<SortBuffers>) sortBuffersField.get(ai);
        SortBuffers buffers = sortBuffers.get();

        Field moveBucketOrderField = AI.class.getDeclaredField("moveBucketOrder");
        moveBucketOrderField.setAccessible(true);
        Object[] bucketOrder = (Object[]) moveBucketOrderField.get(ai);

        IntArrayList reconstructed = new IntArrayList(ordered.size());
        boolean validated = false;

        for (Object bucketObj : bucketOrder) {
            Enum<?> bucketEnum = (Enum<?>) bucketObj;
            IntArrayList bucket = buffers.bucketIndexes[bucketEnum.ordinal()];
            if (bucket.size() > 1) {
                validated = true;
                for (int i = 1; i < bucket.size(); i++) {
                    int previousIndex = bucket.getInt(i - 1);
                    int currentIndex = bucket.getInt(i);
                    int previousScore = buffers.scoreBuffer[previousIndex];
                    int currentScore = buffers.scoreBuffer[currentIndex];
                    assertTrue(previousScore >= currentScore,
                            () -> "Scores should be non-increasing within a bucket" +
                                    " (prev=" + previousScore + ", curr=" + currentScore + ")");
                    if (previousScore == currentScore) {
                        int previousMove = buffers.moveBuffer[previousIndex];
                        int currentMove = buffers.moveBuffer[currentIndex];
                        assertTrue(previousMove >= currentMove,
                                () -> "Moves should tie-break by descending id" +
                                        " (prev=" + Move.convertIntToMove(previousMove) +
                                        ", curr=" + Move.convertIntToMove(currentMove) + ")");
                    }
                }
            }
            for (int i = 0; i < bucket.size(); i++) {
                reconstructed.add(buffers.moveBuffer[bucket.getInt(i)]);
            }
        }

        assertTrue(validated, "Expected at least one bucket to contain multiple moves for ordering checks");
        assertArrayEquals(ordered.toIntArray(), reconstructed.toIntArray(),
                "writeBucket should consume the pre-sorted buckets without reordering");
    }

    private static int indexOf(IntArrayList moves, int target) {
        for (int i = 0; i < moves.size(); i++) {
            if (moves.getInt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static int findFirst(IntArrayList moves, java.util.function.IntPredicate predicate) {
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (predicate.test(move)) {
                return move;
            }
        }
        return -1;
    }

    private static String describeMoveOrder(IntArrayList moves) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < moves.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(Move.convertIntToMove(moves.getInt(i)));
        }
        return builder.toString();
    }
}

