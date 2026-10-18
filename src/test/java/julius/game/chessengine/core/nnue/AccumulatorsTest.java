package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.MoveGen;
import julius.game.chessengine.core.Position;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lazy incremental updates and both storage back ends must give exactly the evaluation of a full refresh. */
class AccumulatorsTest {

    @Test
    void bundledNetworkIncrementalScalarAndVectorAgreeWithRefresh() {
        Network net = Network.loadDefault();
        assertNotNull(net, "bundled network");
        assertTrue(compareAlongRandomGames(net, 7) > 1000);
    }

    @Test
    void kingBucketNetworkRebuildsOnBucketChangesAndAgreesWithRefresh() throws IOException {
        Network net = Network.read(new ByteArrayInputStream(randomVersion3(64, 8, 3)));
        assertEquals(8, net.kingBuckets);
        assertTrue(net.mirror);
        assertTrue(compareAlongRandomGames(net, 11) > 1000);
    }

    @Test
    void kingBucketFeaturesMirrorAndBucket() throws IOException {
        Network net = Network.read(new ByteArrayInputStream(randomVersion3(64, 8, 3)));
        // White king g1 (file >= e): squares mirrored, bucket of g1 = bucket of b1 = 1
        int pawnE2 = net.feature(Position.WHITE, Position.piece(Position.WHITE, Position.PAWN), 12, 6);
        assertEquals(1 * 768 + 0 + 0 * 64 + (12 ^ 7), pawnE2);
        // Black's view: a black king on g8 is g1 in its frame, a white pawn on e2 is e7 there, mirrored to d7
        int whitePawnForBlack = net.feature(Position.BLACK, Position.piece(Position.WHITE, Position.PAWN), 12, 62);
        assertEquals(1 * 768 + 384 + (12 ^ 56 ^ 7), whitePawnForBlack);
        assertTrue(!net.sameFrame(Position.WHITE, 6, 7));   // g1 -> h1: buckets 1 -> 0 (mirrored b1 -> a1)
        assertTrue(net.sameFrame(Position.WHITE, 16, 17));  // a3 -> b3: both bucket 6, same board half
        assertTrue(!net.sameFrame(Position.WHITE, 4, 3));   // e1 -> d1: same bucket, but crosses the mirror line
        assertTrue(net.sameFrame(Position.BLACK, 48, 49));  // a7 -> b7 is a2 -> b2 for Black: both bucket 4
    }

    /** Random games: incremental (scalar and vector back end) vs full refresh at every evaluated ply. */
    private static int compareAlongRandomGames(Network net, long seed) {
        int maxPly = 128;
        Accumulators scalar = new ScalarAccumulators(net, maxPly);
        // the Vector API back end when the test JVM has the module (Maven adds it), else scalar twice
        Accumulators vector = Accumulators.create(net, maxPly);
        SplittableRandom rng = new SplittableRandom(seed);
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
                    // a new instance has an empty cache: this is a true from-scratch build
                    Accumulators fresh = new ScalarAccumulators(net, 1);
                    fresh.refresh(p, 0);
                    int expected = fresh.evaluate(p, 0);
                    assertEquals(expected, scalar.evaluate(p, ply + 1), "scalar at ply " + ply + " " + p.toFen());
                    assertEquals(expected, vector.evaluate(p, ply + 1), "vector at ply " + ply + " " + p.toFen());
                    checked++;
                }
            }
        }
        return checked;
    }

    /** A version 3 network with random weights: 8 king buckets (castled-king layout), mirrored. */
    static byte[] randomVersion3(int hidden, int buckets, long seed) {
        byte[] map = {
                0, 1, 2, 3, 3, 2, 1, 0,
                4, 4, 5, 5, 5, 5, 4, 4,
                6, 6, 6, 6, 6, 6, 6, 6,
                7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
                7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7};
        int kb = 8;
        int w1 = 768 * kb * hidden;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 * 6 + 4 + 4 + 64 + 2 * (w1 + hidden + 2 * hidden * buckets) + 4 * buckets)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'A', 'L', 'N', 'N'}).putInt(3).putInt(hidden).putInt(255).putInt(256).putInt(400)
                .putInt(buckets).putInt(kb).putInt(1).put(map);
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = 0; i < w1; i++) b.putShort((short) (r.nextInt(201) - 100));
        for (int i = 0; i < hidden; i++) b.putShort((short) (r.nextInt(301) - 50));
        for (int i = 0; i < 2 * hidden * buckets; i++) b.putShort((short) (r.nextInt(1015) - 507));
        for (int i = 0; i < buckets; i++) b.putInt(r.nextInt(20001) - 10000);
        return b.array();
    }
}
