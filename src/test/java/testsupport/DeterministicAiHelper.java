package testsupport;

import julius.game.chessengine.ai.AI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper utilities to keep AI tests deterministic.
 */
public final class DeterministicAiHelper {

    private static final Logger log = LogManager.getLogger(DeterministicAiHelper.class);

    private DeterministicAiHelper() {
    }

    public static AutoCloseable withSingleThread(AI ai) {
        int previousThreads = ai.getSearchThreads();
        int previousLazy;
        try {
            previousLazy = (int) TestUtils.readField(ai, "lazySmpThreads");
            TestUtils.writeField(ai, "lazySmpThreads", 1);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to force single threaded mode", e);
        }
        ai.setSearchThreads(1);
        log.debug("Forced AI into single-threaded mode");
        return () -> {
            ai.setSearchThreads(previousThreads);
            try {
                TestUtils.writeField(ai, "lazySmpThreads", previousLazy);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to restore Lazy SMP threads", e);
            }
        };
    }

    public static AutoCloseable withShortTimeLimit(AI ai, long millis) {
        long previous = ai.getTimeLimit();
        ai.setTimeLimit(millis);
        log.debug("Temporary time limit set to {} ms", millis);
        return () -> {
            ai.setTimeLimit(previous);
        };
    }
}

