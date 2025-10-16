package julius.game.chessengine.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineMoveGenerationProfilerTest {

    @AfterEach
    void tearDown() {
        Engine.disableMoveGenerationProfiling();
    }

    @Test
    void profilingCapturesGenerationAndCacheHits() {
        Engine.enableMoveGenerationProfiling();
        Engine engine = new Engine();
        Engine.resetMoveGenerationProfiling();

        Engine.MoveGenerationStats initial = Engine.snapshotMoveGenerationStats();
        assertThat(initial.generationCalls()).isZero();
        assertThat(initial.cacheHits()).isZero();
        assertThat(initial.generatedMoves()).isZero();

        IntArrayList firstCall = engine.getAllLegalMoves();
        Engine.MoveGenerationStats afterFirst = Engine.snapshotMoveGenerationStats();
        assertThat(afterFirst.generationCalls()).isEqualTo(1);
        assertThat(afterFirst.cacheHits()).isZero();
        assertThat(afterFirst.generatedMoves()).isEqualTo(firstCall.size());

        IntArrayList secondCall = engine.getAllLegalMoves();
        Engine.MoveGenerationStats afterSecond = Engine.snapshotMoveGenerationStats();
        assertThat(afterSecond.generationCalls()).isEqualTo(1);
        assertThat(afterSecond.cacheHits()).isEqualTo(1);
        assertThat(secondCall.size()).isEqualTo(firstCall.size());
    }
}
