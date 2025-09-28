package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameStateEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationPipelineTest {

    @Test
    void appliesModuleWeightsDuringAggregation() {
        EvaluationModule attack = new StaticModule(40, 10);
        EvaluationModule safety = new StaticModule(-20, 30);
        EvaluationWeights weights = EvaluationWeights.of(Map.of(
                StaticModule.class.getSimpleName(), new EvaluationWeights.ModuleWeight(2.0, 0.5)
        ));
        EvaluationPipeline pipeline = new EvaluationPipeline(List.of(attack, safety), weights);

        EvaluationContext context = EvaluationContext.from(new BitBoard(), GameStateEnum.PLAY);
        pipeline.initialize(context);

        assertThat(pipeline.getMidgameScore()).isEqualTo(40);
        assertThat(pipeline.getEndgameScore()).isEqualTo(20);
    }

    private static final class StaticModule implements EvaluationModule {
        private final int mid;
        private final int end;
        private boolean dirty = true;

        private StaticModule(int mid, int end) {
            this.mid = mid;
            this.end = end;
        }

        @Override
        public void initialize(EvaluationContext context) {
            dirty = true;
        }

        @Override
        public void evaluate(EvaluationContext context) {
            dirty = false;
        }

        @Override
        public void applyMove(MoveContext moveContext) {
            dirty = true;
        }

        @Override
        public void undoMove(MoveContext moveContext) {
            dirty = true;
        }

        @Override
        public int getMidgameScore() {
            return mid;
        }

        @Override
        public int getEndgameScore() {
            return end;
        }

        @Override
        public boolean isDirty() {
            return dirty;
        }

        @Override
        public void markDirty() {
            dirty = true;
        }
    }
}
