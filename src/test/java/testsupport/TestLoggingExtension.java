package testsupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * JUnit 5 extension providing structured logging around test execution.
 */
public class TestLoggingExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback, TestWatcher {

    private static final Logger log = LogManager.getLogger(TestLoggingExtension.class);
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(TestLoggingExtension.class);
    private static final String START_TIME = "start-time";

    static {
        System.setProperty("chessengine.searchThreads", System.getProperty("chessengine.searchThreads", "1"));
        System.setProperty("chessengine.lazySmpThreads", System.getProperty("chessengine.lazySmpThreads", "1"));
        System.setProperty("chessengine.rootParallelLimit", System.getProperty("chessengine.rootParallelLimit", "24"));
        System.setProperty("chessengine.tt.mb", System.getProperty("chessengine.tt.mb", "64"));
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        log.info(">>> START {}", describe(context));
        context.getStore(NAMESPACE).put(context.getUniqueId() + START_TIME, System.nanoTime());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        long start = Optional.ofNullable((Long) context.getStore(NAMESPACE)
                        .remove(context.getUniqueId() + START_TIME, Long.class))
                .orElse(System.nanoTime());
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        log.info("<<< END {} ({} ms)", describe(context), duration.toMillis());
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        log.info("✔ SUCCESS {}", describe(context));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.error("✖ FAILURE {} -> {}", describe(context), cause.getMessage(), cause);
        logThreadDump();
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        log.warn("⚠ ABORTED {} -> {}", describe(context), cause == null ? "no reason" : cause.getMessage(), cause);
    }

    private static String describe(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName() + "." + context.getRequiredTestMethod().getName();
    }

    private void logThreadDump() {
        StringBuilder dump = new StringBuilder();
        dump.append(System.lineSeparator()).append("--- THREAD DUMP ---------------------------------------------------").append(System.lineSeparator());
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            dump.append('[').append(thread.getState()).append("] ").append(thread.getName()).append(" (#")
                    .append(thread.getId()).append(')').append(System.lineSeparator());
            for (StackTraceElement element : entry.getValue()) {
                dump.append("    at ").append(element).append(System.lineSeparator());
            }
            dump.append(System.lineSeparator());
        }
        dump.append("------------------------------------------------------------------");
        log.error(dump.toString());
    }
}
