package julius.game.chessengine.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility to expose the application version from the generated pom.properties.
 */
public final class VersionInfo {

    private static final String POM_PATH = "/META-INF/maven/julius.game/chess-engine/pom.properties";
    private static final String UNKNOWN = "unknown";

    private VersionInfo() {
    }

    public static String getVersion() {
        try (InputStream is = VersionInfo.class.getResourceAsStream(POM_PATH)) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("version", UNKNOWN);
            }
        } catch (IOException ignored) {
        }
        return UNKNOWN;
    }
}
