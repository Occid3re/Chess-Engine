package julius.game.chessengine.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Utility to expose the application version from the generated pom.properties.
 */
public final class VersionInfo {

    private static final String POM_PROPERTIES = "/META-INF/maven/julius.game/chess-engine/pom.properties";
    private static final String UNKNOWN = "unknown";
    private static final Path LOCAL_POM = Path.of("pom.xml");

    private VersionInfo() {
    }

    public static String getVersion() {
        String fromProps = readFromPomProperties();
        return UNKNOWN.equals(fromProps) ? readFromPomXml() : fromProps;
    }

    private static String readFromPomProperties() {
        try (InputStream is = VersionInfo.class.getResourceAsStream(POM_PROPERTIES)) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("version", UNKNOWN);
            }
        } catch (IOException ignored) {
        }
        return UNKNOWN;
    }

    private static String readFromPomXml() {
        if (!Files.exists(LOCAL_POM)) {
            return UNKNOWN;
        }
        try (InputStream is = Files.newInputStream(LOCAL_POM)) {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
            Node project = doc.getDocumentElement();
            for (int i = 0; i < project.getChildNodes().getLength(); i++) {
                Node node = project.getChildNodes().item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "version".equals(node.getNodeName())) {
                    return node.getTextContent();
                }
            }
        } catch (Exception ignored) {
        }
        return UNKNOWN;
    }
}
