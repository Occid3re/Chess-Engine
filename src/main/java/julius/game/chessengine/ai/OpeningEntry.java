package julius.game.chessengine.ai;

import java.util.List;

/**
 * Represents a single move in the opening book linked to the position it
 * originated from.  Metadata like the originating opening name and the
 * remaining line (continuation) allows callers to surface book information
 * or bias selection towards deeper preparation.
 */
public record OpeningEntry(int move, String openingName, int ply, List<Integer> continuation) {

    public OpeningEntry {
        if (openingName == null || openingName.isBlank()) {
            openingName = "Unknown";
        }
        continuation = continuation == null ? List.of() : List.copyOf(continuation);
    }
}
