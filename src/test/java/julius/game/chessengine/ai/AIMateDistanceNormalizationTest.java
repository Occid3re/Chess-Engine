package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.tuning.Tuning;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AIMateDistanceNormalizationTest {

    @BeforeEach
    void refreshTuning() {
        Tuning.refresh();
    }

    @Test
    void adjustMateFromChildPrefersFasterMate() throws Exception {
        AI ai = new AI(new Engine(), AiTuning.defaults());
        try {
            Method adjust = AI.class.getDeclaredMethod("adjustMateFromChild", double.class);
            adjust.setAccessible(true);

            double winningMate = Score.CHECKMATE - 10;
            double adjustedWin = (double) adjust.invoke(ai, winningMate);
            assertThat(adjustedWin).isEqualTo(winningMate - 1);

            double losingMate = -Score.CHECKMATE + 12;
            double adjustedLoss = (double) adjust.invoke(ai, losingMate);
            assertThat(adjustedLoss).isEqualTo(losingMate + 1);

            double nonMate = Score.CHECKMATE - 5000;
            double unchanged = (double) adjust.invoke(ai, nonMate);
            assertThat(unchanged).isEqualTo(nonMate);
        } finally {
            ai.shutdown();
        }
    }

    @Test
    void mateScoreRoundTripsAcrossTranspositionTableNormalization() throws Exception {
        AI ai = new AI(new Engine(), AiTuning.defaults());
        try {
            Method toStored = AI.class.getDeclaredMethod("toStoredMateScore", double.class, int.class);
            Method fromStored = AI.class.getDeclaredMethod("fromStoredMateScore", double.class, int.class);
            toStored.setAccessible(true);
            fromStored.setAccessible(true);

            int ply = 5;

            double winningMate = Score.CHECKMATE - 20;
            double storedWin = (double) toStored.invoke(ai, winningMate, ply);
            assertThat(storedWin).isEqualTo(winningMate + ply);
            double restoredWin = (double) fromStored.invoke(ai, storedWin, ply);
            assertThat(restoredWin).isEqualTo(winningMate);

            double losingMate = -Score.CHECKMATE + 28;
            double storedLoss = (double) toStored.invoke(ai, losingMate, ply);
            assertThat(storedLoss).isEqualTo(losingMate - ply);
            double restoredLoss = (double) fromStored.invoke(ai, storedLoss, ply);
            assertThat(restoredLoss).isEqualTo(losingMate);

            double nonMate = Score.CHECKMATE - 5000;
            double storedNonMate = (double) toStored.invoke(ai, nonMate, ply);
            assertThat(storedNonMate).isEqualTo(nonMate);
            double restoredNonMate = (double) fromStored.invoke(ai, storedNonMate, ply);
            assertThat(restoredNonMate).isEqualTo(nonMate);
        } finally {
            ai.shutdown();
        }
    }
}
