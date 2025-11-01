package julius.game.chessengine.tablebase;

import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.TestSyzygySupport;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Assumptions;

final class TablebaseTestSupport {

    private TablebaseTestSupport() {
    }

    static void assumeSyzygyConfigured() {
        Assumptions.assumeTrue(TestSyzygySupport.isSyzygyConfigured(), () ->
                "Syzygy directory not configured. Set 'chessengine.syzygy.paths' or 'chessengine.syzygy.path' "
                        + "(or the matching env vars) to run tablebase-backed tests.");
    }

    static boolean isSyzygyConfigured() {
        return TestSyzygySupport.isSyzygyConfigured();
    }

    static TablebaseServiceRestorer overrideScoreTablebase(SyzygyTablebaseService service) {
        if (service == null) {
            throw new IllegalArgumentException("service");
        }
        SyzygyTablebaseService previous = Score.getTablebaseService();
        Score.setTablebaseService(service);
        return new TablebaseServiceRestorer(previous);
    }

    static final class TablebaseServiceRestorer implements AutoCloseable {

        private final SyzygyTablebaseService previous;
        private boolean closed;

        private TablebaseServiceRestorer(SyzygyTablebaseService previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (previous == null) {
                Score.clearTablebaseService();
            } else {
                Score.setTablebaseService(previous);
            }
            closed = true;
        }
    }
}
