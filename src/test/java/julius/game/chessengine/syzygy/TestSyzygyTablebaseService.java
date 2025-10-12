package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.engine.GameState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only extension exposing helpers to create {@link SyzygyTablebaseService}
 * instances backed by deterministic tablebase clients.
 */
public final class TestSyzygyTablebaseService extends SyzygyTablebaseService {

    private final TestClient testClient;

    public TestSyzygyTablebaseService(TablebaseClient client, int cacheSize) {
        super(client, cacheSize);
        this.testClient = client instanceof TestClient ? (TestClient) client : null;
    }

    private TestSyzygyTablebaseService(TestClient client, int cacheSize) {
        super(client, cacheSize);
        this.testClient = client;
    }

    public static TestSyzygyTablebaseService fromResponses(Map<String, SyzygyProbeResult> responses) {
        return new TestSyzygyTablebaseService(new TestClient(responses), 16);
    }

    public List<String> getProbedFens() {
        return testClient == null ? List.of() : testClient.snapshot();
    }

    private static final class TestClient implements TablebaseClient {

        private final Map<String, SyzygyProbeResult> responses;
        private final CopyOnWriteArrayList<String> probedFens = new CopyOnWriteArrayList<>();

        private TestClient(Map<String, SyzygyProbeResult> responses) {
            this.responses = responses;
        }

        @Override
        public Optional<SyzygyProbeResult> probe(BitBoard board) {
            String fen = toFen(board);
            probedFens.add(fen);
            return Optional.ofNullable(responses.get(fen));
        }

        private List<String> snapshot() {
            return List.copyOf(probedFens);
        }

        private String toFen(BitBoard board) {
            GameState state = new GameState(new BitBoard(board));
            return FEN.translateBoardToFEN(board, state).getRenderBoard();
        }
    }
}
