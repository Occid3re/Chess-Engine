package julius.game.chessengine.syzygy;

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
        public Optional<SyzygyProbeResult> probe(String fen) {
            probedFens.add(fen);
            return Optional.ofNullable(responses.get(fen));
        }

        private List<String> snapshot() {
            return List.copyOf(probedFens);
        }
    }
}
