package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Position;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UncertaintyHeadTest {

    @Test
    void bundledHeadBelongsToTheBundledNetworkAndGivesSmallProbabilities() {
        UncertaintyHead head = UncertaintyHead.loadDefault();
        assertNotNull(head, "core/nnue-unc.bin must match core/nnue.bin (retrain it after a new network)");
        String[] fens = {
                Position.START_FEN,
                "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
                "8/8/4k3/8/8/4K3/4P3/8 w - - 0 1",
        };
        for (String fen : fens) {
            double u = head.predict(new Position(fen));
            assertTrue(u > 0 && u < 0.5, fen + " -> " + u);
        }
    }

    @Test
    void headOfAnotherNetworkIsIgnored() throws Exception {
        byte[] data;
        try (InputStream in = UncertaintyHead.class.getResourceAsStream(UncertaintyHead.RESOURCE)) {
            assertNotNull(in);
            data = in.readAllBytes();
        }
        int crc = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(8);
        Network net = Network.loadDefault();
        assertNotNull(UncertaintyHead.read(net, crc, data));
        assertNull(UncertaintyHead.read(net, crc + 1, data));
    }
}
