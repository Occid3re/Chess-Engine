package julius.game.chessengine.syzygy;

import java.util.Optional;

/**
 * Shared Syzygy configuration helpers for integration-style tests. The helpers probe the
 * JVM/system properties used by the production engine so tests can opt in to the real native
 * tablebases when the caller supplies the relevant directories.
 */
public final class TestSyzygySupport {

    private static final String PATH_PROPERTY = "chessengine.syzygy.paths";
    private static final String ALT_PATH_PROPERTY = "chessengine.syzygy.path";
    private static final String PATH_ENV = "CHESSENGINE_SYZYGY_PATH";
    private static final String ALT_PATH_ENV = "SYZYGY_PATH";
    private static final String MAX_PIECES_PROPERTY = "chessengine.syzygy.maxPieces";
    private static final String CACHE_SIZE_PROPERTY = "chessengine.syzygy.cacheSize";

    private TestSyzygySupport() {
    }

    public static boolean isSyzygyConfigured() {
        return isNonEmpty(System.getProperty(PATH_PROPERTY))
                || isNonEmpty(System.getProperty(ALT_PATH_PROPERTY))
                || isNonEmpty(System.getenv(PATH_ENV))
                || isNonEmpty(System.getenv(ALT_PATH_ENV));
    }

    public static Optional<String> resolveConfiguredDirectories() {
        if (!isSyzygyConfigured()) {
            return Optional.empty();
        }
        return Optional.of(resolveDirectories());
    }

    public static Optional<SyzygyTablebaseService> maybeCreateServiceFromConfiguration() {
        Optional<String> directories = resolveConfiguredDirectories();
        if (directories.isEmpty()) {
            return Optional.empty();
        }
        int maxPieces = parsePositiveInt(System.getProperty(MAX_PIECES_PROPERTY), 6);
        int cacheSize = parsePositiveInt(System.getProperty(CACHE_SIZE_PROPERTY), 65_536);

        SyzygyTablebaseService service = new SyzygyTablebaseService(directories.get(), maxPieces, cacheSize);
        service.ensureReady();
        return Optional.of(service);
    }

    private static String resolveDirectories() {
        String directories = firstNonEmpty(
                System.getProperty(PATH_PROPERTY),
                System.getProperty(ALT_PATH_PROPERTY),
                System.getenv(PATH_ENV),
                System.getenv(ALT_PATH_ENV)
        );
        if (directories == null) {
            throw new IllegalStateException("Syzygy directory configuration missing despite opt-in");
        }
        return directories;
    }

    private static int parsePositiveInt(String value, int defaultValue) {
        if (!isNonEmpty(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (isNonEmpty(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
