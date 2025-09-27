package julius.game.chessengine.ai;

import julius.game.chessengine.pgn.OpeningPgnReader;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handles discovery of PGN resources, parsing and construction of the
 * {@link OpeningEntry} map used by {@link OpeningBook}.  The loader also takes
 * care of persisting a cache to speed-up subsequent startups.
 */
@Log4j2
final class OpeningBookLoader {

    private static final String DEFAULT_RESOURCE_PATTERN = "classpath*:opening/*.pgn";
    private static final String OPENING_DIR_PROP = "chess.opening.dir";
    private static final String OPENING_DIR_ENV = "CHESS_OPENING_DIR";
    private static final String CACHE_PATH_PROP = "chess.opening.cache";
    private static final String CACHE_PATH_ENV = "CHESS_OPENING_CACHE";

    private final OpeningPgnReader reader = new OpeningPgnReader();

    record Result(Map<Long, List<OpeningEntry>> entries, boolean fromCache) {}

    Result load() {
        List<OpeningResource> resources = locateResources();
        if (resources.isEmpty()) {
            log.info("No PGN files discovered for opening book");
            return new Result(Collections.emptyMap(), false);
        }
        resources.sort(Comparator.comparing(OpeningResource::openingName));
        String fingerprint = computeFingerprint(resources);
        OpeningBookCache cache = new OpeningBookCache(resolveCachePath());
        OpeningBookCache.CachePayload cached = cache.readIfPresent();
        if (cached != null && Objects.equals(cached.fingerprint(), fingerprint)) {
            log.info("Loaded opening book from cache ({} entries)", cached.entries().size());
            return new Result(OpeningBookCache.toMap(cached), true);
        }
        Map<Long, List<OpeningEntry>> parsed = parseResources(resources);
        cache.write(OpeningBookCache.fromMap(fingerprint, parsed));
        log.info("Parsed {} PGN resources into {} unique hashes", resources.size(), parsed.size());
        return new Result(parsed, false);
    }

    private Map<Long, List<OpeningEntry>> parseResources(List<OpeningResource> resources) {
        Map<Long, List<OpeningEntry>> aggregated = new HashMap<>();
        for (OpeningResource resource : resources) {
            List<OpeningPgnReader.ParsedGame> games = reader.parse(resource.data());
            for (OpeningPgnReader.ParsedGame game : games) {
                applyGame(resource.openingName(), game, aggregated);
            }
        }
        aggregated.replaceAll((hash, entries) -> Collections.unmodifiableList(new ArrayList<>(entries)));
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(aggregated));
    }

    private void applyGame(String openingName, OpeningPgnReader.ParsedGame game, Map<Long, List<OpeningEntry>> aggregated) {
        List<Integer> moves = game.moves();
        List<Long> hashes = game.hashes();
        for (int i = 0; i < moves.size() && i < hashes.size(); i++) {
            long hash = hashes.get(i);
            int move = moves.get(i);
            List<Integer> continuation = moves.subList(i + 1, moves.size());
            OpeningEntry entry = new OpeningEntry(move, openingName, i + 1, continuation);
            aggregated.computeIfAbsent(hash, k -> new ArrayList<>()).add(entry);
        }
    }

    private String computeFingerprint(List<OpeningResource> resources) {
        List<byte[]> blobs = resources.stream()
                .map(res -> combineNameAndData(res.openingName(), res.data()))
                .collect(Collectors.toList());
        return reader.fingerprint(blobs);
    }

    private byte[] combineNameAndData(String name, byte[] data) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[nameBytes.length + 1 + data.length];
        System.arraycopy(nameBytes, 0, combined, 0, nameBytes.length);
        combined[nameBytes.length] = 0;
        System.arraycopy(data, 0, combined, nameBytes.length + 1, data.length);
        return combined;
    }

    private List<OpeningResource> locateResources() {
        String configuredPath = firstNonBlank(System.getProperty(OPENING_DIR_PROP), System.getenv(OPENING_DIR_ENV));
        if (configuredPath != null) {
            Path directory = Paths.get(configuredPath);
            if (Files.isDirectory(directory)) {
                return readFromDirectory(directory);
            }
            log.warn("Configured opening directory {} does not exist", directory);
        }
        return readFromClasspath();
    }

    private List<OpeningResource> readFromDirectory(Path directory) {
        try {
            List<OpeningResource> resources = new ArrayList<>();
            try (var stream = Files.walk(directory)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pgn"))
                        .forEach(path -> resources.add(loadPath(path)));
            }
            return resources;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read directory " + directory, ex);
        }
    }

    private OpeningResource loadPath(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            return new OpeningResource(openingNameFromFile(path.getFileName().toString()), data);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read PGN file " + path, ex);
        }
    }

    private List<OpeningResource> readFromClasspath() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(DEFAULT_RESOURCE_PATTERN);
            List<OpeningResource> result = new ArrayList<>();
            for (Resource resource : resources) {
                if (!resource.exists()) {
                    continue;
                }
                byte[] data = resource.getContentAsByteArray();
                String filename = Optional.ofNullable(resource.getFilename()).orElse("opening");
                result.add(new OpeningResource(openingNameFromFile(filename), data));
            }
            return result;
        } catch (IOException ex) {
            log.warn("Unable to read classpath PGNs", ex);
            return Collections.emptyList();
        }
    }

    private Path resolveCachePath() {
        String configured = firstNonBlank(System.getProperty(CACHE_PATH_PROP), System.getenv(CACHE_PATH_ENV));
        if (configured != null) {
            return Paths.get(configured);
        }
        return Paths.get("target", "opening-book-cache.json");
    }

    private String openingNameFromFile(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot == -1 ? filename : filename.substring(0, dot);
        return base.replace('_', ' ').trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record OpeningResource(String openingName, byte[] data) {
        OpeningResource {
            Objects.requireNonNull(openingName, "openingName");
            Objects.requireNonNull(data, "data");
        }
    }
}
