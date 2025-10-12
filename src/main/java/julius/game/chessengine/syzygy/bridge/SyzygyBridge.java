/*
 * MIT License
 *
 * Copyright (c) 2019 Laurens Winkelhagen
 *
 * Source: https://github.com/ljgw/syzygy-bridge
 */
package julius.game.chessengine.syzygy.bridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * A bridge between Java and the Fathom library to access Syzygy tablebases.
 *
 * <p>The static initializer extracts the bundled JNI library into a temporary
 * directory based on the running platform and loads it via {@link System#load}.
 * This allows the engine to vendor the native binaries alongside the Java
 * sources.</p>
 */
public final class SyzygyBridge {

    private static final Logger LOG = LogManager.getLogger();
    private static final String LIBRARY_NAME = "JSyzygy";
    private static final String LIBRARY_OVERRIDE_PROPERTY = "chessengine.syzygy.nativeLibrary";
    private static final String LIBRARY_OVERRIDE_ENV = "CHESSENGINE_SYZYGY_NATIVE";
    private static boolean libLoaded = false;
    private static int tbLargest = 0;

    private SyzygyBridge() {
    }

    /*
     * just loading the SyzygyBridge class will trigger loading the JSyzygy library via JNI.
     */
    static {
        try {
            loadBundledLibrary();
        } catch (URISyntaxException | IOException | UnsatisfiedLinkError | RuntimeException e) {
            LOG.warn("unable to load JSyzygy library", e);
        }
    }

    private static void loadBundledLibrary() throws URISyntaxException, IOException {
        if (libLoaded) {
            return;
        }
        String override = firstNonEmpty(System.getProperty(LIBRARY_OVERRIDE_PROPERTY),
                System.getenv(LIBRARY_OVERRIDE_ENV));
        if (override != null && !override.isBlank()) {
            Path overridePath = toPath(override.trim());
            if (overridePath != null && Files.isRegularFile(overridePath)) {
                System.load(overridePath.toAbsolutePath().toString());
                libLoaded = true;
                LOG.info("Loaded {} from override path {}", LIBRARY_NAME, overridePath);
                return;
            }
            LOG.warn("Configured Syzygy native library override '{}' was not found or is not a file.", override);
        }

        Platform platform = detectPlatform();
        if (platform == null) {
            LOG.warn("Unsupported platform. os.name={} os.arch={}",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return;
        }

        String mappedLibraryName = System.mapLibraryName(LIBRARY_NAME);
        Path extracted = extractBundledLibrary(platform);
        if (extracted != null) {
            System.load(extracted.toAbsolutePath().toString());
            libLoaded = true;
            LOG.info("Loaded {} for platform {} from {}", mappedLibraryName, platform, extracted);
            return;
        }

        Path jarfile = Paths.get(SyzygyBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path sibling = jarfile.getParent().resolve(mappedLibraryName);
        if (Files.exists(sibling)) {
            System.load(sibling.toAbsolutePath().toString());
            libLoaded = true;
            LOG.info("Loaded {} located next to the .jar file", mappedLibraryName);
            return;
        }

        LOG.info("Falling back to java.library.path for {}", mappedLibraryName);
        System.loadLibrary(LIBRARY_NAME);
        libLoaded = true;
    }

    private static Path toPath(String candidate) {
        try {
            return Paths.get(candidate);
        } catch (RuntimeException ex) {
            LOG.warn("Invalid path configured for Syzygy native library: {}", candidate, ex);
            return null;
        }
    }

    private static Platform detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        for (Platform platform : Platform.values()) {
            if (platform.matches(osName, arch)) {
                return platform;
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static Path extractBundledLibrary(Platform platform) throws IOException {
        String resourcePath = platform.resourcePath();
        try (InputStream input = SyzygyBridge.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                LOG.info("No bundled native library found at {}", resourcePath);
                return null;
            }
            Path tempDir = Files.createTempDirectory("jsyzygy-");
            Path tempLib = tempDir.resolve(platform.libraryFile());
            Files.copy(input, tempLib, StandardCopyOption.REPLACE_EXISTING);
            tempLib.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();
            return tempLib;
        }
    }

    /**
     *
     * @return true iff the JSyzygy JNI library is loaded.
     */
    public static boolean isLibLoaded() {
        return libLoaded;
    }

    /**
     * determine if Syzygy tablebases are available for the supplied number of pieces (including kings)
     *
     * @param piecesLeft the number of pieces on the board
     * @return true if the JSyzygy JNI library is loaded and tablebases suitable for the supplied number of pieces are loaded.
     */
    public static boolean isAvailable(int piecesLeft) {
        return libLoaded && piecesLeft <= tbLargest;
    }

    /**
     * Load the Syzygy tablebases (init in Fathom)
     *
     * @param path the location of the tablebases
     * @return the supported size of the loaded tablebases
     */
    public static synchronized int load(String path) {
        LOG.info("loading syzygy tablebases from {}", path);
        if (tbLargest > 0) {
            LOG.warn("Syzygy tablebases are already loaded");
            return tbLargest;
        }
        boolean result = init(path);

        if (result) {
            tbLargest = getTBLargest();
        } else {
            tbLargest = -1;
        }

        return tbLargest;
    }

    /**
     * Returns the supported size of the loaded tablebases
     *
     * @return the supported size of the loaded tablebases
     */
    public static int getSupportedSize() {
        return tbLargest;
    }

    /**
     * Returns a result containing the Win/Draw/Loss characteristics of the position. Notes: assumes castling is no longer possible and that there is no 50 move rule.
     *
     * @param white   all white pieces (bitboard)
     * @param black   all black pieces (bitboard)
     * @param kings   all kings (bitboard)
     * @param queens  all queens (bitboard)
     * @param rooks   all rooks (bitboard)
     * @param bishops all bishops (bitboard)
     * @param knights all knights (bitboard)
     * @param pawns   all pawns (bitboard)
     * @param ep      the square where an En Passant capture can take place (or 0 if there is no En Passant)
     * @param turn    true if white is to move, false if black is.
     * @return WDL result (see c code)
     */
    public static synchronized int probeSyzygyWDL(long white, long black, long kings, long queens, long rooks, long bishops, long knights, long pawns, int ep, boolean turn) { //NOSONAR
        return probeWDL(white, black, kings, queens, rooks, bishops, knights, pawns, ep, turn);
    }

    /**
     * Returns a result containing the distance to zero (and a move that will decrease the distance to zero). Note: assumes castling is no longer possible.
     *
     * @param white   all white pieces (bitboard)
     * @param black   all black pieces (bitboard)
     * @param kings   all kings (bitboard)
     * @param queens  all queens (bitboard)
     * @param rooks   all rooks (bitboard)
     * @param bishops all bishops (bitboard)
     * @param knights all knights (bitboard)
     * @param pawns   all pawns (bitboard)
     * @param rule50  The 50-move half-move clock
     * @param ep      the square where an En Passant capture can take place (or 0 if there is no En Passant)
     * @param turn    true if white is to move, false if black is.
     * @return DTZ result (see c code)
     */
    public static synchronized int probeSyzygyDTZ(long white, long black, long kings, long queens, long rooks, long bishops, long knights, long pawns, int rule50, int ep, boolean turn) { //NOSONAR
        return probeDTZ(white, black, kings, queens, rooks, bishops, knights, pawns, rule50, ep, turn);
    }

    private static native boolean init(String path);

    private static native int getTBLargest();

    private static native int probeWDL(long white, long black, long kings, long queens, long rooks, long bishops, long knights, long pawns, int ep, boolean turn); //NOSONAR

    private static native int probeDTZ(long white, long black, long kings, long queens, long rooks, long bishops, long knights, long pawns, int rule50, int ep, boolean turn); //NOSONAR

    private enum Platform {
        LINUX("linux", new String[]{"amd64", "x86_64"}, "linux-x86_64", "libJSyzygy.so"),
        WINDOWS("win", new String[]{"amd64", "x86_64"}, "win-x86_64", "JSyzygy.dll");

        private final String osToken;
        private final String[] architectures;
        private final String resourceDirectory;
        private final String libraryFile;

        Platform(String osToken, String[] architectures, String resourceDirectory, String libraryFile) {
            this.osToken = osToken;
            this.architectures = architectures;
            this.resourceDirectory = resourceDirectory;
            this.libraryFile = libraryFile;
        }

        boolean matches(String osName, String arch) {
            return osName.contains(osToken) && Arrays.stream(architectures).anyMatch(arch::contains);
        }

        String resourcePath() {
            return "natives/" + resourceDirectory + "/" + libraryFile;
        }

        String libraryFile() {
            return libraryFile;
        }

        @Override
        public String toString() {
            return resourceDirectory;
        }
    }
}
