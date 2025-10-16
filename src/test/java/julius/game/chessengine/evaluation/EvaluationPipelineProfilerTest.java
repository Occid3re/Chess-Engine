package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationPipelineProfilerTest {

    @AfterEach
    void tearDown() {
        EvaluationPipeline.disableProfiling();
    }

    @Test
    void profilingCountsRefreshesAndModules() {
        BitBoard board = new BitBoard();
        Score score = Score.initializeScore(board);

        EvaluationPipeline.enableProfiling();
        EvaluationPipeline.resetProfiling();

        score.refresh(board, GameStateEnum.PLAY);
        EvaluationPipeline.EvaluationStats first = EvaluationPipeline.snapshotProfiling();

        assertThat(first.refreshCalls()).isEqualTo(1);
        assertThat(first.modulesEvaluated()).isPositive();

        score.refresh(board, GameStateEnum.PLAY);
        EvaluationPipeline.EvaluationStats second = EvaluationPipeline.snapshotProfiling();

        assertThat(second.refreshCalls()).isEqualTo(1);
        assertThat(second.modulesEvaluated()).isEqualTo(first.modulesEvaluated());
    }
}
