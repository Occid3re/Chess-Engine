package julius.game.chessengine.helper;

import lombok.extern.log4j.Log4j2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static julius.game.chessengine.helper.BitHelper.FileMasks;

@Log4j2
public class RookHelper {

    private static final String ROOK_MAGIC_NUMBERS_PATH = "/magic/rook_magic_numbers.txt";
    private static final String ROOK_MAGIC_NUMBERS_PATH_write = "src/main/resources" + ROOK_MAGIC_NUMBERS_PATH;

    int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}; // Rook directions

    public long[] rookMasks = new long[64];
    public long[][] rookAttacks = new long[64][];

    public int[] rookBits = new int[64];
    public long[] rookMagics = new long[64]; // To store the found magic numbers

    boolean[] squareMagicFound = new boolean[64];

    private static RookHelper instance = null;

    public final static int[] WHITE_ROOK_POSITIONAL_VALUES = {
            // Ranks 1 to 8 (for a White Rook)
            0, 0, 0, 0, 0, 0, 0, 0,     // Rank 1 (Back rank, initial position)
            5, 10, 10, 10, 10, 10, 10, 5, // Rank 2
            5, 10, 10, 10, 10, 10, 10, 5, // Rank 3
            5, 10, 15, 15, 15, 15, 10, 5, // Rank 4
            5, 10, 15, 20, 20, 15, 10, 5, // Rank 5
            5, 10, 15, 20, 20, 15, 10, 5, // Rank 6
            10, 20, 25, 30, 30, 25, 20, 10, // Rank 7
            15, 25, 30, 35, 35, 30, 25, 15  // Rank 8 (Advanced position)
    };

    public final static int[] BLACK_ROOK_POSITIONAL_VALUES = {
            15, 25, 30, 35, 35, 30, 25, 15, // Rank 1 (Advanced position)
            10, 20, 25, 30, 30, 25, 20, 10, // Rank 2
            5, 10, 15, 20, 20, 15, 10, 5, // Rank 3
            5, 10, 15, 20, 20, 15, 10, 5,   // Rank 4
            5, 10, 15, 15, 15, 15, 10, 5,   // Rank 5
            5, 10, 10, 10, 10, 10, 10, 5,   // Rank 6
            5, 10, 10, 10, 10, 10, 10, 5,   // Rank 7
            0, 0, 0, 0, 0, 0, 0, 0          // Rank 8 (Back rank, initial position)
    };

    public RookHelper() {
        loadMagicNumbers();
        // First, generate and store occupancy masks
        for (int square = 0; square < 64; square++) {
            rookMasks[square] = generateOccupancyMask(square);
        }
        initializeRookAttacks();
        initializeRookBits();
    }

    public static RookHelper getInstance() {
        if (instance == null) {
            instance = new RookHelper();
        }
        return instance;
    }

    public static int countRooksOnOpenFiles(long rooksBitboard, long allPawns) {
        int count = 0;
        for (char file = 'a'; file <= 'h'; file++) {
            if (isRookOnOpenFile(rooksBitboard, allPawns, file)) {
                count++;
            }
        }
        return count;
    }

    public static int countRooksOnHalfOpenFiles(long rooksBitboard, long ownPawnsBitboard, long opponentPawnsBitboard) {
        int count = 0;
        for (char file = 'a'; file <= 'h'; file++) {
            if (isRookOnHalfOpenFile(rooksBitboard, ownPawnsBitboard, opponentPawnsBitboard, file)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isRookOnOpenFile(long rooksBitboard, long allPawns, char file) {
        long fileBitboard = FileMasks[file - 'a'];
        // Check if the file has no pawns and if the rook is on this file
        return (allPawns & fileBitboard) == 0 && (rooksBitboard & fileBitboard) != 0;
    }

    private static boolean isRookOnHalfOpenFile(long rooksBitboard, long ownPawnsBitboard, long opponentPawnsBitboard, char file) {
        long fileBitboard = FileMasks[file - 'a'];
        // Check if the file has opponent's pawns but not own pawns, and if the rook is on this file
        return (ownPawnsBitboard & fileBitboard) == 0 && (opponentPawnsBitboard & fileBitboard) != 0 && (rooksBitboard & fileBitboard) != 0;
    }

    public void findMagicNumbersParallel(int time) {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ConcurrentHashMap<Integer, Long> magicNumbers = new ConcurrentHashMap<>();

        // Map to store square indices and their corresponding index counts
        Map<Integer, Integer> squareIndexCounts = new HashMap<>();

        int total = 0;
        // Calculate index counts for each square
        for (int square = 0; square < 64; square++) {
            long mask = rookMasks[square];
            int indexCount = calculateIndexCount(rookMagics[square], square, mask);
            squareIndexCounts.put(square, indexCount);
            total += indexCount;
        }
        log.info(" --- Rook attacks take up a size of {} --- ", total);

        // Create a list of square indices sorted by index counts in descending order
        List<Integer> squares = squareIndexCounts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        for (int square : squares) {
            Set<Long> uniqueAttacks = new HashSet<>();
            boolean duplicatesFound = false;

            for (long attacks : rookAttacks[square]) {
                if (!uniqueAttacks.add(attacks)) { // add() returns false if the item was already in the set
                    duplicatesFound = true;
                    break;
                }
            }

            if (duplicatesFound) {
                // Submit the task only if duplicates are found, indicating a need for optimization
                executor.submit(() -> findMagicNumberForSquare(square, magicNumbers));
            }
            else {
                log.info("Rook square {} is fully optimized size {}", square, squareIndexCounts.get(square));
            }
        }

        // Shutdown executor and wait for termination
        executor.shutdown();
        try {
            if (!executor.awaitTermination(time, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        writeMagicNumbersToFile(magicNumbers);
    }

    private void findMagicNumberForSquare(int square, ConcurrentHashMap<Integer, Long> magicNumbers) {
        long mask = rookMasks[square];
        Set<Long> occupancies = generateAllOccupancies(mask);
        int minIndices = calculateIndexCount(rookMagics[square], square, mask);
        log.info("Rook Square: {}, has a size of {}", square, minIndices);

        while (true) {
            long magicCandidate = randomMagicNumber();
            Map<Integer, Long> indexToOccupancy = new HashMap<>();
            boolean collision = false;

            for (long occupancy : occupancies) {
                int index = transform(occupancy, magicCandidate, mask);
                Long existingOccupancy = indexToOccupancy.get(index);

                if (existingOccupancy != null && calculateRookMoves(square, occupancy) != calculateRookMoves(square, existingOccupancy)) {
                    collision = true;
                    break;
                }

                indexToOccupancy.put(index, occupancy);
            }

            if (!collision && indexToOccupancy.size() < minIndices) {
                minIndices = indexToOccupancy.size(); // Update to the new lower value
                rookMagics[square] = magicCandidate; // Update the magic number
                squareMagicFound[square] = true;
                log.info("Rook Optimized magic number found for square " + square + ": " + magicCandidate);
                magicNumbers.put(square, magicCandidate);
                break;
            }
        }
    }

    private int calculateIndexCount(long magicNumber, int square, long mask) {
        Set<Long> occupancies = generateAllOccupancies(mask);
        Set<Integer> indices = new HashSet<>();

        for (long occupancy : occupancies) {
            int index = transform(occupancy, magicNumber, mask);
            indices.add(index);
        }
        return indices.size();
    }

    public void initializeRookAttacks() {
        for (int square = 0; square < 64; square++) {
            long mask = rookMasks[square];
            Set<Long> occupancies = generateAllOccupancies(mask);
            rookAttacks[square] = new long[occupancies.size()];

            for (long occupancy : occupancies) {
                int index = transform(occupancy, rookMagics[square], mask);
                rookAttacks[square][index] = calculateRookMoves(square, occupancy);
            }
        }
    }

    public void initializeRookBits() {
        for (int square = 0; square < 64; square++) {
            rookBits[square] = Long.bitCount(rookMasks[square]);
        }
    }

    public Set<Long> generateAllOccupancies(long mask) {
        Set<Long> occupancies = new HashSet<>();
        int numberOfBits = Long.bitCount(mask);

        // Generate all possible combinations of bits within the mask
        for (int i = 0; i < (1 << numberOfBits); i++) {
            long occupancy = 0L;
            int bitIndex = 0;
            for (int j = 0; j < 64; j++) {
                if ((mask & (1L << j)) != 0) {
                    if ((i & (1 << bitIndex)) != 0) {
                        occupancy |= (1L << j);
                    }
                    bitIndex++;
                }
            }
            occupancies.add(occupancy);
        }
        return occupancies;
    }

    public long calculateRookMoves(int square, long occupancy) {
        long moves = 0L;
        int row = square / 8, col = square % 8;

        for (int[] dir : directions) {
            int r = row + dir[0], c = col + dir[1]; // Move one step in the direction initially

            while (r >= 0 && r < 8 && c >= 0 && c < 8) {
                long positionBit = 1L << (r * 8 + c); // Calculate the bit for the current position

                moves |= positionBit; // Add this square to the moves
                if ((occupancy & positionBit) != 0) {
                    break; // Stop if there's a piece in the way
                }


                r += dir[0]; // Move in the row direction
                c += dir[1]; // Move in the column direction
            }
        }

        return moves;
    }


    public long generateOccupancyMask(int square) {
        long mask = 0L;
        int row = square / 8, col = square % 8;

        // Include squares the rook can move to, excluding the edges and the rook's current square
        for (int i = 1; i < 7; i++) {
            if (col - i > 0) mask |= (1L << (square - i));      // Left
            if (col + i < 7) mask |= (1L << (square + i));     // Right
            if (row - i > 0) mask |= (1L << (square - 8 * i)); // Up
            if (row + i < 7) mask |= (1L << (square + 8 * i)); // Down
        }
        return mask;
    }

    private long randomMagicNumber() {
        return new Random().nextLong();
    }

    public int transform(long occupancy, long magicNumber, long mask) {
        return (int) ((occupancy * magicNumber) >>> (64 - Long.bitCount(mask)));
    }

    public void loadMagicNumbers() {
        try (InputStream is = getClass().getResourceAsStream(ROOK_MAGIC_NUMBERS_PATH)) {
            assert is != null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) {
                        int square = Integer.parseInt(parts[0]);
                        squareMagicFound[square] = true;

                        long magicNumber = Long.parseLong(parts[1]);
                        rookMagics[square] = magicNumber;
                    }
                }
            }
        } catch (IOException | NullPointerException e) {
            log.error("Error reading magic numbers from file", e);
        }
    }

    private void writeMagicNumbersToFile(ConcurrentHashMap<Integer, Long> magicNumbers) {
        File file = new File(ROOK_MAGIC_NUMBERS_PATH_write);
        Map<Integer, Long> existingNumbers = new HashMap<>();

        // Load existing magic numbers from the file
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) {
                        int square = Integer.parseInt(parts[0]);
                        long magicNumber = Long.parseLong(parts[1]);
                        existingNumbers.put(square, magicNumber);
                    }
                }
            } catch (IOException e) {
                log.error("Error reading magic numbers from file", e);
            }
        }

        // Update with new magic numbers
        existingNumbers.putAll(magicNumbers);

        // Write updated magic numbers back to the file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) { // false to overwrite the file
            for (Map.Entry<Integer, Long> entry : existingNumbers.entrySet()) {
                writer.write(entry.getKey() + ":" + entry.getValue() + "\n");
            }
        } catch (IOException e) {
            log.error("Error writing magic numbers to file", e);
        }
    }

    public long calculateMovesUsingRookMagic(int square, long occupancy) {
        // Calculate the index using the magic number
        int index = this.transform(occupancy, this.rookMagics[square], this.rookMasks[square]);
        // Retrieve the moves from the rookAttacks table
        return this.rookAttacks[square][index];
    }

}
