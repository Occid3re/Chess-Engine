package julius.game.chessengine.helper;

import lombok.extern.log4j.Log4j2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
public class BishopHelper {

    private static final String BISHOP_MAGIC_NUMBERS_PATH = "/magic/bishop_magic_numbers.txt";
    private static final String BISHOP_MAGIC_NUMBERS_PATH_write = "src/main/resources" + BISHOP_MAGIC_NUMBERS_PATH;

    public static final int[] BISHOP_MIDGAME_POSITIONAL_VALUES = {
            // R1
            -20,-10,-10,-10,-10,-10,-10,-20,
            // R2
            -10,  0,  0,  0,  0,  0,  0,-10,
            // R3
            -10,  0,  5, 10, 10,  5,  0,-10,
            // R4
            -10,  5,  5, 10, 10,  5,  5,-10,
            // R5
            -10,  0, 10, 10, 10, 10,  0,-10,
            // R6
            -10, 10, 10, 10, 10, 10, 10,-10,
            // R7
            -10,  5,  0,  0,  0,  0,  5,-10,
            // R8
            -20,-10,-10,-10,-10,-10,-10,-20
    };

    public static final int[] BISHOP_ENDGAME_POSITIONAL_VALUES = {
            // R1
            -20,-10,-10,-10,-10,-10,-10,-20,
            // R2
            -10,  0,  0,  0,  0,  0,  0,-10,
            // R3
            -10,  0,  5, 10, 10,  5,  0,-10,
            // R4
            -10,  5,  5, 10, 10,  5,  5,-10,
            // R5
            -10,  0, 10, 10, 10, 10,  0,-10,
            // R6
            -10, 10, 10, 10, 10, 10, 10,-10,
            // R7
            -10,  5,  0,  0,  0,  0,  5,-10,
            // R8
            -20,-10,-10,-10,-10,-10,-10,-20
    };

    // Bishop directions
    private static final int[][] directions = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    public final long[] bishopMasks = new long[64];
    public final long[][] bishopAttacks = new long[64][];
    public final int[] bishopBits = new int[64];
    public final long[] bishopMagics = new long[64];

    private final boolean[] squareMagicFound = new boolean[64];

    private static BishopHelper instance = null;

    private BishopHelper() {
        loadMagicNumbers();
        for (int square = 0; square < 64; square++) {
            bishopMasks[square] = generateOccupancyMask(square);
            bishopBits[square] = Long.bitCount(bishopMasks[square]);
            bishopAttacks[square] = buildAttackTableForSquare(square, bishopMagics[square], bishopMasks[square]);
        }
    }

    public static BishopHelper getInstance() {
        if (instance == null) {
            instance = new BishopHelper();
        }
        return instance;
    }

    // ----------------------- PUBLIC API USED BY TESTS -----------------------

    public void findMagicNumbersParallel(int timeMinutes) {
        // Prepare per-square status and order by difficulty (more bits first).
        List<Integer> squares = new ArrayList<>(64);
        for (int sq = 0; sq < 64; sq++) squares.add(sq);
        squares.sort((a, b) -> Integer.compare(bishopBits[b], bishopBits[a]));

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ConcurrentHashMap<Integer, Long> newlyFound = new ConcurrentHashMap<>();

        AtomicInteger tasks = new AtomicInteger(0);
        long totalEntries = 0;
        for (int sq = 0; sq < 64; sq++) totalEntries += (1L << bishopBits[sq]);
        log.info(" --- Bishop perfect tables require {} entries total --- ", totalEntries);

        for (int square : squares) {
            long mask = bishopMasks[square];
            int bits = bishopBits[square];
            long magic = bishopMagics[square];

            if (magic != 0 && isPerfectMagic(square, mask, bits, magic)) {
                // Already perfect with the current magic and in-memory table consistent.
                continue;
            }

            // Mine this square.
            tasks.incrementAndGet();
            executor.submit(() -> {
                long[] occs = enumerateOccupanciesArray(mask);
                long[] attacks = bishopAttacksFor(square, occs);
                long[] table = new long[1 << bits];

                long found = findPerfectMagicForSquare(bits, mask, occs, attacks, table);
                bishopMagics[square] = found;
                squareMagicFound[square] = true;

                // Replace the in-memory attack table so tests right after mining use the new one.
                bishopAttacks[square] = table;

                newlyFound.put(square, found);
                log.info("Bishop perfect magic @{} found: {}", square, found);
            });
        }

        // Shutdown executor and wait.
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeMinutes, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        // Persist only new/updated magics.
        if (!newlyFound.isEmpty()) {
            writeMagicNumbersToFile(newlyFound);
        } else {
            log.info("All bishop magics are already perfect. Nothing to mine.");
        }
    }

    public Set<Long> generateAllOccupancies(long mask) {
        // Keep method signature for your tests; implement with fast carry-rippler internally.
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        int[] bits = maskToBitPositions(mask);
        int n = bits.length;
        int size = 1 << n;
        for (int i = 0; i < size; i++) {
            long occ = 0L;
            for (int k = 0; k < n; k++) {
                if ((i & (1 << k)) != 0) occ |= (1L << bits[k]);
            }
            set.add(occ);
        }
        return set;
    }

    public long calculateBishopMoves(int square, long occupancy) {
        long moves = 0L;
        int row = square / 8, col = square % 8;

        for (int[] dir : directions) {
            int r = row + dir[0], c = col + dir[1];
            while (r >= 0 && r < 8 && c >= 0 && c < 8) {
                long bb = 1L << (r * 8 + c);
                moves |= bb;
                if ((occupancy & bb) != 0) break;
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
            // Exclude board edges from mask (only interior 1..6).
            while (r >= 1 && r <= 6 && c >= 1 && c <= 6) {
                mask |= (1L << (r * 8 + c));
                r += direction[0];
                c += direction[1];
            }
        }
        return mask;
    }

    public int transform(long occupancy, long magicNumber, long mask) {
        // Keep compatibility with your tests.
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
        int index = this.transform(occupancy, this.bishopMagics[square], this.bishopMasks[square]);
        return this.bishopAttacks[square][index];
    }

    // ----------------------- INTERNALS -----------------------

    private static int[] maskToBitPositions(long mask) {
        int n = Long.bitCount(mask);
        int[] pos = new int[n];
        int idx = 0;
        while (mask != 0) {
            int lsb = Long.numberOfTrailingZeros(mask);
            pos[idx++] = lsb;
            mask &= (mask - 1);
        }
        return pos;
    }

    private static long[] enumerateOccupanciesArray(long mask) {
        int[] bits = maskToBitPositions(mask);
        int n = bits.length;
        long[] occs = new long[1 << n];
        for (int i = 0; i < occs.length; i++) {
            long occ = 0L;
            for (int k = 0; k < n; k++) {
                if ((i & (1 << k)) != 0) occ |= (1L << bits[k]);
            }
            occs[i] = occ;
        }
        return occs;
    }

    private long[] bishopAttacksFor(int square, long[] occs) {
        long[] attacks = new long[occs.length];
        for (int i = 0; i < occs.length; i++) {
            attacks[i] = calculateBishopMoves(square, occs[i]);
        }
        return attacks;
    }

    private static long rand64Sparse() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return r.nextLong() & r.nextLong() & r.nextLong();
    }

    private static int transformBits(long occupancy, long magic, int bits) {
        return (int) ((occupancy * magic) >>> (64 - bits));
    }

    private boolean isPerfectMagic(int square, long mask, int bits, long magic) {
        if (magic == 0) return false;
        int size = 1 << bits;
        final long EMPTY = Long.MIN_VALUE;
        long[] trial = new long[size];
        Arrays.fill(trial, EMPTY);

        long[] occs = enumerateOccupanciesArray(mask);
        for (long occ : occs) {
            int idx = transformBits(occ, magic, bits);
            long atk = calculateBishopMoves(square, occ);
            long cur = trial[idx];
            if (cur == EMPTY) trial[idx] = atk;
            else if (cur != atk) return false;
        }

        // If we get here, the current in-memory table should match; rebuild if size differs.
        if (bishopAttacks[square] == null || bishopAttacks[square].length != size) {
            bishopAttacks[square] = trial;
        } else {
            // Ensure table aligns; if not, replace to avoid false negatives in tests.
            bishopAttacks[square] = trial;
        }
        return true;
    }

    private long findPerfectMagicForSquare(int bits, long mask, long[] occs, long[] attacks, long[] tableOut) {
        final long EMPTY = Long.MIN_VALUE;
        long[] trial = new long[1 << bits];

        while (true) {
            long magic = rand64Sparse();
            // Weak candidate rejection: ensure enough high-bit dispersion.
            if (Long.bitCount((mask * magic) >>> 56) < 6) continue;

            Arrays.fill(trial, EMPTY);
            boolean ok = true;
            for (int i = 0; i < occs.length; i++) {
                int idx = transformBits(occs[i], magic, bits);
                long atk = attacks[i];
                long cur = trial[idx];
                if (cur == EMPTY) trial[idx] = atk;
                else if (cur != atk) { ok = false; break; }
            }
            if (ok) {
                System.arraycopy(trial, 0, tableOut, 0, trial.length);
                return magic;
            }
        }
    }

    private long[] buildAttackTableForSquare(int square, long magic, long mask) {
        int bits = Long.bitCount(mask);
        long[] occs = enumerateOccupanciesArray(mask);
        long[] table = new long[1 << bits];
        if (magic == 0) {
            // Fill with baseline (still consistent indices even if not perfect).
            for (long occ : occs) {
                int idx = transformBits(occ, 0L, bits);
                table[idx] = calculateBishopMoves(square, occ);
            }
            return table;
        }
        for (long occ : occs) {
            int idx = transformBits(occ, magic, bits);
            table[idx] = calculateBishopMoves(square, occ);
        }
        return table;
    }

    private void writeMagicNumbersToFile(ConcurrentHashMap<Integer, Long> magicNumbers) {
        File file = new File(BISHOP_MAGIC_NUMBERS_PATH_write);
        Map<Integer, Long> existingNumbers = new HashMap<>();

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

        existingNumbers.putAll(magicNumbers);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
            for (Map.Entry<Integer, Long> entry : existingNumbers.entrySet()) {
                writer.write(entry.getKey() + ":" + entry.getValue() + "\n");
            }
        } catch (IOException e) {
            log.error("Error writing magic numbers to file", e);
        }
    }
}
