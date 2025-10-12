package julius.game.chessengine.syzygy;

import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
final class SyzygyPathResolver {

    private static final String TABLEBASE_TOKEN = ".rtb";
    private static final int DIRECTORY_SCAN_DEPTH = 2;

    private SyzygyPathResolver() {
    }

    static Optional<String> sanitize(String directories) {
        if (directories == null) {
            return Optional.empty();
        }
        List<String> tokens = splitDirectories(directories);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }

        Set<Path> resolved = new LinkedHashSet<>();
        for (String token : tokens) {
            Path candidate = toPath(token);
            if (candidate == null) {
                continue;
            }
            if (!Files.exists(candidate)) {
                log.warn("Ignoring non-existent Syzygy directory: {}", candidate);
                continue;
            }
            if (Files.isRegularFile(candidate)) {
                Path parent = candidate.getParent();
                if (parent != null) {
                    appendResolved(resolved, parent);
                } else {
                    log.warn("Syzygy file {} does not have a parent directory; skipping.", candidate);
                }
                continue;
            }
            if (!Files.isDirectory(candidate)) {
                log.warn("Ignoring Syzygy path that is neither file nor directory: {}", candidate);
                continue;
            }
            appendResolved(resolved, candidate);
        }

        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(resolved.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator)));
    }

    private static void appendResolved(Set<Path> sink, Path base) {
        List<Path> discovered = resolveProbeDirectories(base);
        for (Path path : discovered) {
            sink.add(path);
        }
    }

    private static List<Path> resolveProbeDirectories(Path base) {
        Path normalised = base.toAbsolutePath().normalize();
        List<Path> discovered = new ArrayList<>();
        if (containsTablebaseFiles(normalised)) {
            discovered.add(normalised);
        }
        try (Stream<Path> stream = Files.walk(normalised, DIRECTORY_SCAN_DEPTH)) {
            discovered.addAll(stream
                    .filter(path -> !path.equals(normalised))
                    .filter(Files::isDirectory)
                    .filter(SyzygyPathResolver::containsTablebaseFiles)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList()));
        } catch (IOException ex) {
            log.warn("Failed to inspect potential Syzygy directory {}", normalised, ex);
        }

        if (discovered.isEmpty()) {
            log.warn("Ignoring Syzygy directory {} because no tablebase files were found", normalised);
        }

        return discovered;
    }

    private static boolean containsTablebaseFiles(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(path -> Files.isRegularFile(path) && looksLikeTablebaseFile(path.getFileName().toString()));
        } catch (IOException ex) {
            log.warn("Failed to inspect Syzygy directory {}", directory, ex);
            return false;
        }
    }

    private static boolean looksLikeTablebaseFile(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.contains(TABLEBASE_TOKEN);
    }

    private static List<String> splitDirectories(String directories) {
        String separator = File.pathSeparator;
        String regex = Pattern.quote(separator);
        return Arrays.stream(directories.split(regex))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toList());
    }

    private static Path toPath(String candidate) {
        try {
            return Path.of(candidate);
        } catch (InvalidPathException ex) {
            log.warn("Ignoring invalid Syzygy directory path: {}", candidate, ex);
            return null;
        }
    }
}
