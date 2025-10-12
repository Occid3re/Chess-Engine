package julius.game.chessengine.tablebase;

import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Field;

final class TablebaseTestSupport {

    private static final String PROPERTY = "chessengine.syzygy.paths";
    private static final String ALT_PROPERTY = "chessengine.syzygy.path";
    private static final String ENV = "CHESSENGINE_SYZYGY_PATH";
    private static final String ALT_ENV = "SYZYGY_PATH";

    private TablebaseTestSupport() {
    }

    static void assumeSyzygyConfigured() {
        Assumptions.assumeTrue(isSyzygyConfigured(), () ->
                "Syzygy directory not configured. Set '" + PROPERTY + "' or '" + ALT_PROPERTY +
                        "' (or the matching env vars) to run tablebase-backed tests.");
    }

    static boolean isSyzygyConfigured() {
        return isNonEmpty(System.getProperty(PROPERTY))
                || isNonEmpty(System.getProperty(ALT_PROPERTY))
                || isNonEmpty(System.getenv(ENV))
                || isNonEmpty(System.getenv(ALT_ENV));
    }

    private static boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static TablebaseServiceRestorer overrideScoreTablebase(SyzygyTablebaseService service) {
        if (service == null) {
            throw new IllegalArgumentException("service");
        }
        try {
            Field field = Score.class.getDeclaredField("TABLEBASE_SERVICE");
            field.setAccessible(true);
            SyzygyTablebaseService previous = (SyzygyTablebaseService) field.get(null);
            Score.setTablebaseService(service);
            return new TablebaseServiceRestorer(field, previous);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to override Score tablebase service", ex);
        }
    }

    static final class TablebaseServiceRestorer implements AutoCloseable {

        private final Field field;
        private final SyzygyTablebaseService previous;
        private boolean closed;

        private TablebaseServiceRestorer(Field field, SyzygyTablebaseService previous) {
            this.field = field;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            try {
                if (previous == null) {
                    field.setAccessible(true);
                    field.set(null, null);
                } else {
                    Score.setTablebaseService(previous);
                }
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to restore Score tablebase service", ex);
            } finally {
                closed = true;
            }
        }
    }
}
