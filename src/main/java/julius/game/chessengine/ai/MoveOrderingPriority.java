package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparators;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persistent store for simple move-ordering priorities keyed by move integers.
 * <p>
 * The table is loaded eagerly on first access and kept in-memory for fast lookups during
 * move ordering. Updates are flushed to disk after every processed game so priority
 * adjustments survive engine restarts.
 */
@Log4j2
public final class MoveOrderingPriority {

    private static final String FILE_PROPERTY = "chessengine.moveOrdering.priorityFile";
    private static final String LEGACY_FLAG_PROPERTY = "ReviseGame";
    private static final String FLAG_PROPERTY = "chessengine.moveOrdering.reviseGame";
    private static final Path DEFAULT_PATH = Paths.get(System.getProperty("user.dir"), "logs", "move-ordering-priority.txt");

    private static final MoveOrderingPriority INSTANCE = new MoveOrderingPriority();

    private final Int2IntOpenHashMap priorities;
    private final Path storagePath;

    private MoveOrderingPriority() {
        this.storagePath = resolveStoragePath();
        this.priorities = new Int2IntOpenHashMap();
        this.priorities.defaultReturnValue(0);
        loadFromDisk();
    }

    public static MoveOrderingPriority getInstance() {
        return INSTANCE;
    }

    public boolean isRevisionEnabled() {
        return Boolean.getBoolean(LEGACY_FLAG_PROPERTY) || Boolean.getBoolean(FLAG_PROPERTY);
    }

    public synchronized int getPriority(int moveInt) {
        return priorities.get(moveInt);
    }

    public synchronized void applyGameResult(IntArrayList moves, boolean won) {
        Objects.requireNonNull(moves, "moves");
        if (moves.isEmpty()) {
            return;
        }

        int delta = won ? 1 : -1;
        boolean changed = false;
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            int updated = priorities.get(move) + delta;
            if (updated == 0) {
                if (priorities.remove(move) != 0) {
                    changed = true;
                }
            } else {
                priorities.put(move, updated);
                changed = true;
            }
        }

        if (changed) {
            persist();
        }
    }

    private Path resolveStoragePath() {
        String override = System.getProperty(FILE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        return DEFAULT_PATH;
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) {
            Path parent = storagePath.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    log.warn("Failed to create move ordering priority directory {}", parent, e);
                }
            }
            return;
        }

        try {
            List<String> lines = Files.readAllLines(storagePath, StandardCharsets.UTF_8);
            for (String raw : lines) {
                if (raw == null) {
                    continue;
                }
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] tokens = line.split("\\s+");
                if (tokens.length < 2) {
                    continue;
                }
                try {
                    int move = Integer.parseInt(tokens[0]);
                    int score = Integer.parseInt(tokens[1]);
                    if (score != 0) {
                        priorities.put(move, score);
                    }
                } catch (NumberFormatException ex) {
                    log.debug("Ignoring malformed priority line '{}': {}", line, ex.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load move ordering priorities from {}", storagePath, e);
        }
    }

    private void persist() {
        IntList keys = new IntArrayList(priorities.size());
        for (Int2IntMap.Entry entry : priorities.int2IntEntrySet()) {
            keys.add(entry.getIntKey());
        }
        keys.sort(IntComparators.NATURAL_COMPARATOR);

        List<String> payload = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            int key = keys.getInt(i);
            payload.add(key + " " + priorities.get(key));
        }

        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(storagePath, payload, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("Failed to persist move ordering priorities to {}", storagePath, e);
        }
    }
}
