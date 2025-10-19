package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MoveOrderingPriorityTest {

    private static Path originalPath;

    @TempDir
    Path tempDir;

    private Path priorityFile;
    private MoveOrderingPriority priority;

    @BeforeAll
    static void captureOriginalStore() throws Exception {
        MoveOrderingPriority instance = MoveOrderingPriority.getInstance();
        var field = MoveOrderingPriority.class.getDeclaredField("storagePath");
        field.setAccessible(true);
        originalPath = (Path) field.get(instance);
    }

    @BeforeEach
    void resetToTemporaryStore() {
        priorityFile = tempDir.resolve("priorities-" + UUID.randomUUID() + ".txt");
        MoveOrderingPriority.resetForTests(priorityFile);
        priority = MoveOrderingPriority.getInstance();
    }

    @AfterAll
    static void restoreOriginalStore() {
        if (originalPath != null) {
            MoveOrderingPriority.resetForTests(originalPath);
        } else {
            MoveOrderingPriority.resetForTests(Paths.get(System.getProperty("user.dir"), "logs", "move-ordering-priority.txt"));
        }
    }

    @Test
    void unknownMovesHaveZeroPriority() {
        int move = 0x12345;
        assertThat(priority.getPriority(move)).isZero();
    }

    @Test
    void gameResultsPersistPositiveAndNegativeCounts() throws IOException {
        IntArrayList winningMoves = new IntArrayList(new int[]{0x10203, 0x50607});
        priority.applyGameResult(winningMoves, true);

        List<String> initial = Files.readAllLines(priorityFile, StandardCharsets.UTF_8);
        assertThat(initial).containsExactly("66051 1", "329223 1");

        priority.applyGameResult(winningMoves, true);
        List<String> afterSecondWin = Files.readAllLines(priorityFile, StandardCharsets.UTF_8);
        assertThat(afterSecondWin).containsExactly("66051 2", "329223 2");

        IntArrayList losingMoves = new IntArrayList(new int[]{0x50607});
        priority.applyGameResult(losingMoves, false); // brings move back to 1
        priority.applyGameResult(losingMoves, false); // removes entry (0)
        priority.applyGameResult(losingMoves, false); // stores negative value

        List<String> afterLosses = Files.readAllLines(priorityFile, StandardCharsets.UTF_8);
        assertThat(afterLosses).containsExactly("66051 2", "329223 -1");

        assertThat(priority.getPriority(0x10203)).isEqualTo(2);
        assertThat(priority.getPriority(0x50607)).isEqualTo(-1);
    }
}
