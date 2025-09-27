package julius.game.chessengine.ai;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Log4j2
public class OpeningBook {
    private static final Map<Long, List<OpeningEntry>> openings = new ConcurrentHashMap<>();
    private final OpeningBookLoader loader;

    private static final class Holder {
        static final OpeningBook INSTANCE = new OpeningBook();
    }

    private OpeningBook() {
        this.loader = new OpeningBookLoader();
        loadOpenings();
    }

    public static OpeningBook getInstance() {
        return Holder.INSTANCE;
    }

    private void loadOpenings() {
        if (!openings.isEmpty()) {
            return;
        }
        OpeningBookLoader.Result result = loader.load();
        openings.clear();
        openings.putAll(result.entries());
        log.info("Opening book initialised with {} unique positions{}", openings.size(),
                result.fromCache() ? " (cache hit)" : "");
    }

    public List<Integer> getMovesForBoardStateHash(long boardStateHash) {
        List<OpeningEntry> entries = openings.get(boardStateHash);
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        for (OpeningEntry entry : entries) {
            unique.add(entry.move());
        }
        return List.copyOf(unique);
    }

    public int getRandomMoveForBoardStateHash(long boardStateHash) {
        List<OpeningEntry> entries = openings.get(boardStateHash);
        if (entries == null || entries.isEmpty()) {
            return -1; // or a default move, depending on how you want to handle this scenario
        }
        OpeningEntry entry = entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
        return entry.move();
    }

    public boolean containsMoveAndBoardStateHash(long boardStateHashBeforeMove, int move) {
        List<OpeningEntry> entries = openings.get(boardStateHashBeforeMove);
        if (entries == null) {
            return false;
        }
        return entries.stream().anyMatch(entry -> entry.move() == move);
    }

    public List<OpeningEntry> getEntriesForHash(long boardStateHash) {
        return openings.getOrDefault(boardStateHash, Collections.emptyList());
    }
}