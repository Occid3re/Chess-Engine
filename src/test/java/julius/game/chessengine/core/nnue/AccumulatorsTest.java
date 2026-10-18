package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.MoveGen;
import julius.game.chessengine.core.Position;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Lazy incremental updates and both storage back ends must give exactly the evaluation of a full refresh. */
class AccumulatorsTest {

    @Test
    void incrementalScalarAndVectorEvaluationsAgreeWithRefresh() {
        Network net = Network.loadDefault();
        assertNotNull(net, "bundled network");
        int maxPly = 128;
        Accumulators scalar = new ScalarAccumulators(net, maxPly);
        // the Vector API back end when the test JVM has the module (Maven adds it), else scalar twice
        Accumulators vector = Accumulators.create(net, maxPly);
        Accumulators fresh = new ScalarAccumulators(net, 1);
        SplittableRandom rng = new SplittableRandom(7);
        int[] moves = new int[256];
        int checked = 0;
        for (int game = 0; game < 40; game++) {
            Position p = new Position();
            scalar.refresh(p, 0);
            vector.refresh(p, 0);
            for (int ply = 0; ply < 100; ply++) {
                int n = MoveGen.generateAll(p, moves, 0);
                int legal = 0;
                for (int i = 0; i < n; i++) if (p.isLegal(moves[i])) moves[legal++] = moves[i];
                if (legal == 0) break;
                int m = moves[rng.nextInt(legal)];
                boolean evaluate = rng.nextInt(3) != 0; // skip some plies: the lazy path must catch up
                scalar.push(p, m, ply);
                vector.push(p, m, ply);
                p.makeMove(m);
                if (evaluate) {
                    fresh.refresh(p, 0);
                    int expected = fresh.evaluate(p, 0);
                    assertEquals(expected, scalar.evaluate(p, ply + 1), "scalar at ply " + ply + " " + p.toFen());
                    assertEquals(expected, vector.evaluate(p, ply + 1), "vector at ply " + ply + " " + p.toFen());
                    checked++;
                }
            }
        }
        assertEquals(true, checked > 1000, "positions checked: " + checked);
    }
}
