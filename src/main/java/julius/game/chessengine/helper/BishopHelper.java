package julius.game.chessengine.helper;

import lombok.extern.log4j.Log4j2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Log4j2
public class BishopHelper {

    private static final String BISHOP_MAGIC_NUMBERS_PATH = "/magic/bishop_magic_numbers.txt";

    private static final String BISHOP_MAGIC_NUMBERS_PATH_write = "src/main/resources" + BISHOP_MAGIC_NUMBERS_PATH;
    public static final int[] BISHOP_POSITIONAL_VALUES = {
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 0, 10, 20, 20, 10, 0, -10,
            -10, 5, 5, 20, 20, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -20, -10, -10, -10, -10, -10, -10, -20
    };

    int[][] directions = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}}; // Bishop directions

    public long[] bishopMasks = new long[64];
    public long[][] bishopAttacks = new long[64][];

    public int[] bishopBits = new int[64];

    public long[] bishopMagics = new long[64]; // To store the found magic numbers

    boolean[] squareMagicFound = new boolean[64];

    private static BishopHelper instance = null;


    private BishopHelper() {
        loadMagicNumbers();
        // First, generate and store occupancy masks
        for (int square = 0; square < 64; square++) {
            bishopMasks[square] = generateOccupancyMask(square);
        }
        initializeBishopAttacks();
        initializeBishopBits();
    }

    public static BishopHelper getInstance() {
        if (instance == null) {
            instance = new BishopHelper();
        }
        return instance;
    }

    public void findMagicNumbersParallel(int time) {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ConcurrentHashMap<Integer, Long> magicNumbers = new ConcurrentHashMap<>();

        // Map to store square indices and their corresponding index counts
        Map<Integer, Integer> squareIndexCounts = new HashMap<>();

        int total = 0;
        // Calculate index counts for each square
        for (int square = 0; square < 64; square++) {
            long mask = bishopMasks[square];
            int indexCount = calculateIndexCount(bishopMagics[square], square, mask);
            squareIndexCounts.put(square, indexCount);
            total += indexCount;
        }
        log.info(" --- Bishop attacks take up a size of {} --- ", total);

        // Create a list of square indices sorted by index counts in descending order
        List<Integer> squares = squareIndexCounts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // Submit a task for each square in the sorted list
        for (int square : squares) {
            Set<Long> uniqueAttacks = new HashSet<>();
            boolean duplicatesFound = false;

            for (long attacks : bishopAttacks[square]) {
                if (!uniqueAttacks.add(attacks)) { // add() returns false if the item was already in the set
                    duplicatesFound = true;
                    break;
                }
            }

            if (duplicatesFound) {
                // Submit the task only if duplicates are found, indicating a need for optimization
                executor.submit(() -> findMagicNumberForSquare(square, magicNumbers));
            } else {
                log.info("Bishop square {} is fully optimized size {}", square, squareIndexCounts.get(square));
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
        long mask = bishopMasks[square];
        Set<Long> occupancies = generateAllOccupancies(mask);
        int minIndices = calculateIndexCount(bishopMagics[square], square, mask);
        log.info("Bishop Square: {}, has a size of {}", square, minIndices);


        while (true) {
            long magicCandidate = randomMagicNumber();
            Map<Integer, Long> indexToOccupancy = new HashMap<>();
            boolean collision = false;

            for (long occupancy : occupancies) {
                int index = transform(occupancy, magicCandidate, mask);
                Long existingOccupancy = indexToOccupancy.get(index);

                if (existingOccupancy != null && calculateBishopMoves(square, occupancy) != calculateBishopMoves(square, existingOccupancy)) {
                    collision = true;
                    break;
                }

                indexToOccupancy.put(index, occupancy);
            }

            if (!collision && indexToOccupancy.size() < minIndices) {
                minIndices = indexToOccupancy.size(); // Update to the new lower value
                bishopMagics[square] = magicCandidate; // Update the magic number
                squareMagicFound[square] = true;
                log.info("Bishop Optimized magic number found for square " + square + ": " + magicCandidate);
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


    private void writeMagicNumbersToFile(ConcurrentHashMap<Integer, Long> magicNumbers) {
        File file = new File(BISHOP_MAGIC_NUMBERS_PATH_write);
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


    public void initializeBishopAttacks() {
        for (int square = 0; square < 64; square++) {
            long mask = bishopMasks[square];
            Set<Long> occupancies = generateAllOccupancies(mask);
            bishopAttacks[square] = new long[occupancies.size()];

            for (long occupancy : occupancies) {
                int index = transform(occupancy, bishopMagics[square], mask);
                bishopAttacks[square][index] = calculateBishopMoves(square, occupancy);
            }
        }
    }

    public void initializeBishopBits() {
        for (int square = 0; square < 64; square++) {
            bishopBits[square] = Long.bitCount(bishopMasks[square]);
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

    public long calculateBishopMoves(int square, long occupancy) {
        long moves = 0L;
        int row = square / 8, col = square % 8;

        for (int[] dir : directions) {
            int r = row + dir[0], c = col + dir[1];
            while (r >= 0 && r < 8 && c >= 0 && c < 8) {
                moves |= (1L << (r * 8 + c));
                if ((occupancy & (1L << (r * 8 + c))) != 0) {
                    break; // blocked by another piece
                }
                r += dir[0];
                c += dir[1];
            }
        }
        return moves;
    }

    public long generateOccupancyMask(int square) {
        long mask = 0L;
        int row = square / 8, col = square % 8;

        for (int[] direction : directions) {
            int r = row + direction[0], c = col + direction[1];
            while (r >= 0 && r < 7 && c >= 0 && c < 7) {
                mask |= (1L << (r * 8 + c));
                r += direction[0];
                c += direction[1];
            }
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
        try (InputStream is = getClass().getResourceAsStream(BISHOP_MAGIC_NUMBERS_PATH)) {
            assert is != null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) {
                        int square = Integer.parseInt(parts[0]);
                        squareMagicFound[square] = true;

                        long magicNumber = Long.parseLong(parts[1]);
                        bishopMagics[square] = magicNumber;
                    }
                }
            }
        } catch (IOException | NullPointerException e) {
            log.error("Error reading magic numbers from file", e);
        }
    }

    public long calculateMovesUsingBishopMagic(int square, long occupancy) {
        // Calculate the index using the magic number
        int index = this.transform(occupancy, this.bishopMagics[square], this.bishopMasks[square]);
        // Retrieve the moves from the bishopAttacks table
        return this.bishopAttacks[square][index];
    }

}
