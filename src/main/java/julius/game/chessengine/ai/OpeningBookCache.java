package julius.game.chessengine.ai;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small helper responsible for serialising/deserialising the opening book to a
 * JSON cache on disk.  The cache is keyed by a content fingerprint so callers
 * can safely reuse precomputed data across restarts until the underlying PGNs
 * change.
 */
@Log4j2
final class OpeningBookCache {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    private final Path cachePath;

    OpeningBookCache(Path cachePath) {
        this.cachePath = Objects.requireNonNull(cachePath, "cachePath");
    }

    record CachePayload(String fingerprint, List<CachedEntry> entries) {}

    record CachedEntry(long hash, int move, String openingName, int ply, List<Integer> continuation) {
        CachedEntry {
            continuation = continuation == null ? List.of() : List.copyOf(continuation);
        }
    }

    CachePayload readIfPresent() {
        if (!Files.exists(cachePath)) {
            return null;
        }
        try (InputStream is = Files.newInputStream(cachePath)) {
            return OBJECT_MAPPER.readValue(is, new TypeReference<>() {});
        } catch (IOException ex) {
            log.warn("Failed to read opening book cache from {}. Falling back to regeneration.", cachePath, ex);
            return null;
        }
    }

    void write(CachePayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            Path parent = cachePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ex) {
            log.warn("Unable to create cache directory for {}", cachePath, ex);
        }
        try (OutputStream os = Files.newOutputStream(cachePath)) {
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(os, payload);
        } catch (IOException ex) {
            log.warn("Failed to write opening book cache to {}", cachePath, ex);
        }
    }

    static Map<Long, List<OpeningEntry>> toMap(CachePayload payload) {
        if (payload == null || payload.entries == null) {
            return Collections.emptyMap();
        }
        Map<Long, List<OpeningEntry>> map = new HashMap<>();
        for (CachedEntry cached : payload.entries) {
            OpeningEntry entry = new OpeningEntry(cached.move, cached.openingName, cached.ply, cached.continuation);
            map.computeIfAbsent(cached.hash, k -> new ArrayList<>()).add(entry);
        }
        map.replaceAll((k, v) -> Collections.unmodifiableList(new ArrayList<>(v)));
        return map;
    }

    static CachePayload fromMap(String fingerprint, Map<Long, List<OpeningEntry>> openings) {
        List<CachedEntry> entries = new ArrayList<>();
        openings.forEach((hash, entryList) -> entryList.forEach(entry ->
                entries.add(new CachedEntry(hash, entry.move(), entry.openingName(), entry.ply(), entry.continuation()))));
        return new CachePayload(fingerprint, entries);
    }
}
