package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyTablebaseServiceTest {

    @Test
    void forwardsBitBoardStateToClient() {
        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/8/8/8/8 w - - 0 1");
        RecordingClient client = new RecordingClient();
        client.response = Optional.of(SyzygyProbeResult.unavailable());

        SyzygyTablebaseService service = new SyzygyTablebaseService(client, 16);
        service.probe(board);

        assertThat(client.lastBoard).isSameAs(board);
    }

    @Test
    void cachesProbesUsingZobristAndClocks() {
        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/8/8/8/8 w - - 0 1");
        RecordingClient client = new RecordingClient();
        client.response = Optional.of(new SyzygyProbeResult(SyzygyWdl.DRAW, OptionalInt.of(0), OptionalInt.empty()));

        SyzygyTablebaseService service = new SyzygyTablebaseService(client, 16);
        Optional<SyzygyProbeResult> first = service.probe(board);
        Optional<SyzygyProbeResult> second = service.probe(board);

        assertThat(first).containsInstanceOf(SyzygyProbeResult.class);
        assertThat(second).isEqualTo(first);
        assertThat(client.invocations).isEqualTo(1);

        board.setHalfmoveClock(1);
        Optional<SyzygyProbeResult> third = service.probe(board);
        assertThat(third).isEqualTo(first);
        assertThat(client.invocations).isEqualTo(2);
    }

    private static final class RecordingClient implements TablebaseClient {
        Optional<SyzygyProbeResult> response = Optional.empty();
        BitBoard lastBoard;
        int invocations = 0;

        @Override
        public Optional<SyzygyProbeResult> probe(BitBoard board) {
            invocations++;
            lastBoard = board;
            return response;
        }
    }
}
