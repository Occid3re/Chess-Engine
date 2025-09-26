package julius.game.chessengine.ai;

import julius.game.chessengine.board.Move;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Log4j2
public class OpeningBook {
    private static final String OPENINGS_FILE_PATH = "/opening/openings.txt";
    private static final Map<Long, List<Integer>> openings = new ConcurrentHashMap<>();

    private static final class Holder {
        static final OpeningBook INSTANCE = new OpeningBook();
    }

    private OpeningBook() {
        loadOpenings();
    }

    public static OpeningBook getInstance() {
        return Holder.INSTANCE;
    }

    private void loadOpenings() {
        // When running from a packaged JAR the resource is immutable, so we only
        // populate the in-memory cache once at start-up.
        if (!openings.isEmpty()) {
            return;
        }

        try (InputStream is = getClass().getResourceAsStream(OPENINGS_FILE_PATH);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    int move = Integer.parseInt(parts[0].trim());
                    long boardStateHash = Long.parseLong(parts[1].trim());
                    openings.computeIfAbsent(boardStateHash, k -> new CopyOnWriteArrayList<>()).add(move);
                }
            }
        } catch (IOException | NullPointerException e) {
            // Handle exceptions or log errors
        }
    }

    public void addOpening(int move, long boardStateHash) {
        List<Integer> existingMoves = openings.computeIfAbsent(boardStateHash, k -> new CopyOnWriteArrayList<>());
        if (!existingMoves.contains(move)) {
            existingMoves.add(move);
            writeOpening(move, boardStateHash); // Writes to the file
        }
    }


    public void writeOpening(int move, long boardStateHash) {
        // This method writes a new opening move to the file
        File file = new File("src/main/resources" + OPENINGS_FILE_PATH);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            writer.write(move + "," + boardStateHash + "\n");
        } catch (IOException e) {
            // Handle exceptions or log errors
        }
    }

    public List<Integer> getMovesForBoardStateHash(long boardStateHash) {
        return openings.getOrDefault(boardStateHash, Collections.emptyList());
    }

    public int getRandomMoveForBoardStateHash(long boardStateHash) {
        List<Integer> moves = getMovesForBoardStateHash(boardStateHash);

        if (moves.isEmpty()) {
            return -1; // or a default move, depending on how you want to handle this scenario
        }
        return moves.get(ThreadLocalRandom.current().nextInt(moves.size()));
    }

    public boolean containsMoveAndBoardStateHash(long boardStateHashBeforeMove, int move) {

        List<Integer> moves = openings.get(boardStateHashBeforeMove);
        if (moves == null) {
            return false;
        }
        return moves.contains(move);
    }
}