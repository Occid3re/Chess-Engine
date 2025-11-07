package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TablebaseRepetitionHeuristicTest {

    @Test
    void treatsTerminalDrawAsDrawCandidate() {
        GameState state = new GameState(new BitBoard());
        state.setState(GameStateEnum.DRAW);

        assertThat(AI.shouldTreatAsTablebaseDraw(state, false)).isTrue();
        assertThat(AI.shouldTreatAsTablebaseDraw(state, true)).isTrue();
    }

    @Test
    void flagsNonZeroingRepetitionRisk() {
        GameState state = new GameState(new BitBoard());
        state.setState(GameStateEnum.PLAY);
        state.getRepetition().clear();
        state.getHashHistory().clear();

        long repeated = 0xABCDEF1234567890L;
        state.recordHash(repeated);
        state.recordHash(repeated); // second occurrence

        assertThat(AI.shouldTreatAsTablebaseDraw(state, false)).isTrue();
        assertThat(AI.shouldTreatAsTablebaseDraw(state, true)).isFalse();
    }

    @Test
    void ignoresFreshPositions() {
        GameState state = new GameState(new BitBoard());
        state.setState(GameStateEnum.PLAY);
        state.getRepetition().clear();
        state.getHashHistory().clear();

        state.recordHash(1L);
        state.recordHash(2L);

        assertThat(AI.shouldTreatAsTablebaseDraw(state, false)).isFalse();
    }
}
