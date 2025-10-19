package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AITest_MoveOrderingPriorityIntegration {

    private static Path originalPath;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void captureOriginalStore() throws Exception {
        MoveOrderingPriority instance = MoveOrderingPriority.getInstance();
        var field = MoveOrderingPriority.class.getDeclaredField("storagePath");
        field.setAccessible(true);
        originalPath = (Path) field.get(instance);
    }

    @BeforeEach
    void resetToTemporaryStore() {
        Path priorityFile = tempDir.resolve("priorities-" + UUID.randomUUID() + ".txt");
        MoveOrderingPriority.resetForTests(priorityFile);
    }

    @AfterAll
    static void restoreOriginalStore() {
        if (originalPath != null) {
            MoveOrderingPriority.resetForTests(originalPath);
        } else {
            MoveOrderingPriority.resetForTests(MoveOrderingPriority.defaultStoragePath());
        }
    }

    @Test
    void priorityRaisesQuietMoveWithinBucket() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen("4k3/8/8/8/8/8/3P4/4K3 w - - 0 1");

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .timeLimitMillis(10L)
                .build();

        AI ai = new AI(engine, tuning);
        IntArrayList legalMoves = engine.getAllLegalMoves();

        assertThat(legalMoves.size()).isGreaterThan(0);

        IntArrayList quietMoves = new IntArrayList();
        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            Move parsed = Move.convertIntToMove(move);
            if (!parsed.isCapture() && !parsed.isPromotionMove() && !parsed.isCastlingMove()) {
                quietMoves.add(move);
            }
        }

        assertThat(quietMoves.size()).isGreaterThanOrEqualTo(2);

        int targetMove = quietMoves.getInt(0);
        int fallbackMove = quietMoves.getInt(1);

        IntArrayList baseline = new IntArrayList(legalMoves);
        ai.sortMovesByEfficiency(baseline, 0, engine.getBoardStateHash(), -1, engine);

        int originalIndex = indexOf(baseline, targetMove);
        int comparisonIndex = indexOf(baseline, fallbackMove);

        assertThat(originalIndex).isGreaterThanOrEqualTo(0);
        assertThat(comparisonIndex).isGreaterThanOrEqualTo(0);

        // Ensure the target move is not already the preferred one to provide a baseline.
        if (originalIndex <= comparisonIndex) {
            // Swap the reference points if the engine already prefers d2d4.
            int temp = targetMove;
            targetMove = fallbackMove;
            fallbackMove = temp;
            originalIndex = comparisonIndex;
        }

        MoveOrderingPriority priority = MoveOrderingPriority.getInstance();
        priority.applyGameResult(new IntArrayList(new int[]{targetMove}), true);

        IntArrayList boosted = new IntArrayList(engine.getAllLegalMoves());
        ai.sortMovesByEfficiency(boosted, 0, engine.getBoardStateHash(), -1, engine);

        int boostedIndex = indexOf(boosted, targetMove);
        int fallbackBoostedIndex = indexOf(boosted, fallbackMove);

        assertThat(boostedIndex)
                .as("priority should reduce the index (raise priority) of the target move")
                .isLessThan(originalIndex);
        assertThat(boostedIndex)
                .as("priority move should stay ahead of fallback after boost")
                .isLessThan(fallbackBoostedIndex);
    }

    private static int indexOf(IntArrayList moves, int target) {
        for (int i = 0; i < moves.size(); i++) {
            if (moves.getInt(i) == target) {
                return i;
            }
        }
        return -1;
    }
}
