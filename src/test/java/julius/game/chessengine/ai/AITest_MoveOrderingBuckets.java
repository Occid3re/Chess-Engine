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

