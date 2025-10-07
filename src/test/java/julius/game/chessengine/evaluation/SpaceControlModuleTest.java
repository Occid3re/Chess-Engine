package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.engine.GameStateEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceControlModuleTest {

    private static final String WHITE_SPACE_ADVANTAGE =
            "4k3/8/8/8/2PP4/2N5/PP3PPP/4K3 w - - 0 1";
    private static final String BLACK_SPACE_ADVANTAGE =
            "4k3/ppp3pp/2n5/2pp4/8/8/8/4K3 w - - 0 1";

    @Test
    void spaceControlRewardsWhiteTerritorialEdge() {
        int spacious = blendedScore(WHITE_SPACE_ADVANTAGE);
        int cramped = blendedScore(BLACK_SPACE_ADVANTAGE);

        assertThat(spacious).isPositive();
        assertThat(cramped).isNegative();
        assertThat(spacious).isGreaterThan(cramped);
    }

    private static int blendedScore(String fen) {
        BitBoard board = FEN.translateFENtoBitBoard(fen);
        SpaceControlModule module = new SpaceControlModule();
        EvaluationPipeline pipeline = new EvaluationPipeline(List.of(module));
        pipeline.initialize(EvaluationContext.from(board, GameStateEnum.PLAY));
        return pipeline.getBlendedScore();
    }
}
