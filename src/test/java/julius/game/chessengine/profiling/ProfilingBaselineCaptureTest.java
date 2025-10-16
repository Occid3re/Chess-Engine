package julius.game.chessengine.profiling;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.Engine.MoveGenerationStats;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.evaluation.EvaluationPipeline;
import julius.game.chessengine.evaluation.EvaluationPipeline.EvaluationStats;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThatCode;

@EnabledIfSystemProperty(named = "chessengine.profile.dump", matches = ".+")
class ProfilingBaselineCaptureTest {

    private static final String OUTPUT_PROPERTY = "chessengine.profile.dump";

    @AfterEach
    void tearDown() {
        Engine.disableMoveGenerationProfiling();
        EvaluationPipeline.disableProfiling();
    }

    @Test
    void captureProfilingSnapshot() {
        String outputRoot = System.getProperty(OUTPUT_PROPERTY);
        Objects.requireNonNull(outputRoot, "Profiling output path required");

        Engine.enableMoveGenerationProfiling();
        Engine.resetMoveGenerationProfiling();
        EvaluationPipeline.enableProfiling();
        EvaluationPipeline.resetProfiling();

        Engine engine = new Engine();
        Score score = Score.initializeScore(new BitBoard());

        // Warm up evaluation pipeline.
        score.refresh(engine.getBitBoard(), GameStateEnum.PLAY);
        score.refresh(engine.getBitBoard(), GameStateEnum.PLAY);

        // Exercise move generation cache hit and regeneration path.
        engine.getAllLegalMoves();
        engine.getAllLegalMoves();

        MoveGenerationStats moveStats = Engine.snapshotMoveGenerationStats();
        EvaluationStats evalStats = EvaluationPipeline.snapshotProfiling();

        Path outputDirectory = Path.of(outputRoot);
        assertThatCode(() -> Files.createDirectories(outputDirectory))
                .doesNotThrowAnyException();

        String filename = "profiling-baseline-" + Instant.now().toString().replace(":", "-") + ".txt";
        Path outputFile = outputDirectory.resolve(filename);
        StringBuilder report = new StringBuilder();
        report.append("Evaluation profiling:\n");
        report.append("  refreshCalls=").append(evalStats.refreshCalls()).append('\n');
        report.append("  modulesEvaluated=").append(evalStats.modulesEvaluated()).append('\n');
        report.append("  refreshNanos=").append(evalStats.refreshNanos()).append('\n');
        report.append("Move generation profiling:\n");
        report.append("  generationCalls=").append(moveStats.generationCalls()).append('\n');
        report.append("  cacheHits=").append(moveStats.cacheHits()).append('\n');
        report.append("  generatedMoves=").append(moveStats.generatedMoves()).append('\n');
        report.append("  generationNanos=").append(moveStats.generationNanos()).append('\n');

        assertThatCode(() -> Files.writeString(
                outputFile,
                report.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )).doesNotThrowAnyException();
    }
}
