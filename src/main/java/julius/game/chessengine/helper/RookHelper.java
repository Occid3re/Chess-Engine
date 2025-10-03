package julius.game.chessengine.helper;

import lombok.extern.log4j.Log4j2;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
public class RookHelper {

    private static final String ROOK_MAGIC_NUMBERS_PATH = "/magic/rook_magic_numbers.txt";
    private static final String ROOK_MAGIC_NUMBERS_PATH_write = "src/main/resources" + ROOK_MAGIC_NUMBERS_PATH;

    private static final long[] PRECOMPUTED_ROOK_MAGICS = new long[] {
            36028936609595408L, // 0
            2323857476446855168L, // 1
            144124259317448836L, // 2
            612498345683846272L, // 3
            144120823207297040L, // 4
            4647715932146237952L, // 5
            180144534867411456L, // 6
            72057732559143168L, // 7
            5189694884176757796L, // 8
            1297107062502723588L, // 9
            2392674742042752L, // 10
            72339107670392864L, // 11
            1267189432714240L, // 12
            9570157798361105L, // 13
            6991979167579373824L, // 14
            1153203118171884800L, // 15
            576497586019385344L, // 16
            150083874340875L, // 17
            141287512612868L, // 18
            2305879293365911560L, // 19
            141287311280128L, // 20
            1268286729748992L, // 21
            5192654768538980354L, // 22
            144117387109138500L, // 23
            108086684188418048L, // 24
            288318338155683844L, // 25
            4904420065081827328L, // 26
            281513633648640L, // 27
            5771362939722285088L, // 28
            1153488854762652160L, // 29
            1152939148343509274L, // 30
            2598577268460650788L, // 31
            -8070309519839723488L, // 32
            189151734650650630L, // 33
            4647855621729689601L, // 34
            18032274196924448L, // 35
            72199465439593472L, // 36
            703704630034944L, // 37
            2305851809668817424L, // 38
            290597520801961L, // 39
            576531157557215232L, // 40
            2305930970680803328L, // 41
            5908746902551265312L, // 42
            20266267043201152L, // 43
            -9220833264371957744L, // 44
            579275536497508368L, // 45
            146371394526052608L, // 46
            45109681949638660L, // 47
            -6016737633340714496L, // 48
            70370944091264L, // 49
            -8646770409622994816L, // 50
            567485577302528L, // 51
            -9078129827751362432L, // 52
            38562106202915072L, // 53
            18031998077633536L, // 54
            -9218582405214354944L, // 55
            3314667467707720961L, // 56
            2306133831112987683L, // 57
            450432667944096001L, // 58
            4503741364455525L, // 59
            564066913886210L, // 60
            3171097671754846210L, // 61
            576536627467780228L, // 62
            290482315568628738L // 63
    };

    // Rook directions

    public final long[] rookMasks = new long[64];
    public final long[][] rookAttacks = new long[64][];
    public final int[] rookBits = new int[64];
    public final long[] rookMagics = new long[64]; // To store the found magic numbers

    private final boolean[] squareMagicFound = new boolean[64];

    private static RookHelper instance = null;

    public final static int[] WHITE_ROOK_MIDGAME_POSITIONAL_VALUES = {
            // R1
            10, 15, 15, 20, 20, 15, 15, 10,
            // R2
            10, 15, 15, 20, 20, 15, 15, 10,
            // R3
            0, 5, 10, 15, 15, 10, 5, 0,
            // R4
            -5, 0, 5, 10, 10, 5, 0, -5,
            // R5
            -5, 0, 10, 15, 15, 10, 0, -5,
            // R6
            -5, 0, 10, 15, 15, 10, 0, -5,
            // R7
            -5, 0, 5, 10, 10, 5, 0, -5,
            // R8
            -5, -5, 0, 0, 0, 0, -5, -5
    };

    public final static int[] WHITE_ROOK_ENDGAME_POSITIONAL_VALUES = {
            // R1
            0, 0, 5, 5, 5, 5, 0, 0,
            // R2
            0, 5, 10, 10, 10, 10, 5, 0,
            // R3
            5, 10, 15, 15, 15, 15, 10, 5,
            // R4
            5, 10, 15, 20, 20, 15, 10, 5,
            // R5
            5, 10, 15, 20, 20, 15, 10, 5,
            // R6
            5, 10, 15, 15, 15, 15, 10, 5,
            // R7
            0, 5, 10, 10, 10, 10, 5, 0,
            // R8
            0, 0, 5, 5, 5, 5, 0, 0
    };

    public final static int[] BLACK_ROOK_MIDGAME_POSITIONAL_VALUES = {
            // mirror of white
            -5, -5, 0, 0, 0, 0, -5, -5, // R1
            -5, 0, 5, 10, 10, 5, 0, -5, // R2
            -5, 0, 10, 15, 15, 10, 0, -5, // R3
            -5, 0, 10, 15, 15, 10, 0, -5, // R4
            -5, 0, 5, 10, 10, 5, 0, -5, // R5
            0, 5, 10, 15, 15, 10, 5, 0, // R6
            10, 15, 15, 20, 20, 15, 15, 10, // R7
            10, 15, 15, 20, 20, 15, 15, 10  // R8
    };

    public final static int[] BLACK_ROOK_ENDGAME_POSITIONAL_VALUES = {
            // mirror of white
            0, 0, 5, 5, 5, 5, 0, 0, // R1
            0, 5, 10, 10, 10, 10, 5, 0, // R2
            5, 10, 15, 15, 15, 15, 10, 5, // R3
            5, 10, 15, 20, 20, 15, 10, 5, // R4
            5, 10, 15, 20, 20, 15, 10, 5, // R5
            5, 10, 15, 15, 15, 15, 10, 5, // R6
            0, 5, 10, 10, 10, 10, 5, 0, // R7
            0, 0, 5, 5, 5, 5, 0, 0  // R8
    };

    public final int[] rookShifts = new int[64];   // = 64 - rookBits[sq]

    public RookHelper() {
        loadMagicNumbers();
        for (int sq = 0; sq < 64; sq++) {
            rookMasks[sq]  = generateOccupancyMask(sq);
            rookBits[sq]   = Long.bitCount(rookMasks[sq]);
            rookShifts[sq] = 64 - rookBits[sq];                 // <-- add
            rookAttacks[sq]= buildAttackTableForSquare(sq, rookMagics[sq], rookMasks[sq]);
        }
    }

    public final long calculateMovesUsingRookMagic(int square, long occupancy) {
        // self-mask to be safe/call-site-agnostic; HotSpot keeps this cheap
        long occMasked = occupancy & rookMasks[square];
        int idx = (int)((occMasked * rookMagics[square]) >>> rookShifts[square]);
        return rookAttacks[square][idx];
    }

    public static RookHelper getInstance() {
        if (instance == null) {
            instance = new RookHelper();
        }
        return instance;
    }

    // ----------------------- Convenience stats -----------------------

    // ----------------------- Mining API (used by your test) -----------------------

    public void findMagicNumbersParallel(int timeMinutes) {
        // Order squares by decreasing difficulty.
        List<Integer> squares = new ArrayList<>(64);
        for (int sq = 0; sq < 64; sq++) squares.add(sq);
        squares.sort((a, b) -> Integer.compare(rookBits[b], rookBits[a]));

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ConcurrentHashMap<Integer, Long> newlyFound = new ConcurrentHashMap<>();

        AtomicInteger tasks = new AtomicInteger(0);
        long totalEntries = 0;
        for (int sq = 0; sq < 64; sq++) totalEntries += (1L << rookBits[sq]);
        log.info(" --- Rook perfect tables require {} entries total --- ", totalEntries);

        for (int square : squares) {
            long mask = rookMasks[square];
            int bits = rookBits[square];
            long magic = rookMagics[square];

            if (magic != 0 && isPerfectMagic(square, mask, bits, magic)) {
                // Already perfect; no work for this square.
                continue;
            }

            tasks.incrementAndGet();
            executor.submit(() -> {
                long[] occs = enumerateOccupanciesArray(mask);
                long[] attacks = rookAttacksFor(square, occs);
                long[] table = new long[1 << bits];

                long found = findPerfectMagicForSquare(bits, mask, occs, attacks, table);
                rookMagics[square] = found;
                squareMagicFound[square] = true;

                // Replace in-memory table so subsequent tests use this.
                rookAttacks[square] = table;

                newlyFound.put(square, found);
                log.info("Rook perfect magic @{} found: {}", square, found);
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeMinutes, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        if (!newlyFound.isEmpty()) {
            writeMagicNumbersToFile(newlyFound);
        } else {
            log.info("All rook magics are already perfect. Nothing to mine.");
        }
    }

    // ----------------------- Move gen & masks -----------------------

    public Set<Long> generateAllOccupancies(long mask) {
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

    public long calculateRookMoves(int square, long occupancy) {
        long moves = 0L;
        int row = square / 8, col = square % 8;

        // Down (increasing row)
        for (int r = row + 1; r < 8; r++) {
            long bb = 1L << (r * 8 + col);
            moves |= bb;
            if ((occupancy & bb) != 0) break;
        }
        // Up (decreasing row)
        for (int r = row - 1; r >= 0; r--) {
            long bb = 1L << (r * 8 + col);
            moves |= bb;
            if ((occupancy & bb) != 0) break;
        }
        // Right (increasing col)
        for (int c = col + 1; c < 8; c++) {
            long bb = 1L << (row * 8 + c);
            moves |= bb;
            if ((occupancy & bb) != 0) break;
        }
        // Left (decreasing col)
        for (int c = col - 1; c >= 0; c--) {
            long bb = 1L << (row * 8 + c);
            moves |= bb;
            if ((occupancy & bb) != 0) break;
        }

        return moves;
    }

    public long generateOccupancyMask(int square) {
        long mask = 0L;
        int row = square / 8, col = square % 8;

        // Exclude board edges (only interior 1..6 columns/rows are included in mask).
        for (int r = row + 1; r <= 6; r++) mask |= 1L << (r * 8 + col);
        for (int r = row - 1; r >= 1; r--) mask |= 1L << (r * 8 + col);
        for (int c = col + 1; c <= 6; c++) mask |= 1L << (row * 8 + c);
        for (int c = col - 1; c >= 1; c--) mask |= 1L << (row * 8 + c);

        return mask;
    }

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

    private long[] rookAttacksFor(int square, long[] occs) {
        long[] attacks = new long[occs.length];
        for (int i = 0; i < occs.length; i++) {
            attacks[i] = calculateRookMoves(square, occs[i]);
        }
        return attacks;
    }

    // ----------------------- Transform & IO -----------------------

    public int transform(long occupancy, long magicNumber, long mask) {
        // Keep compatibility with your tests.
        return (int) ((occupancy * magicNumber) >>> (64 - Long.bitCount(mask)));
    }

    public void loadMagicNumbers() {
        System.arraycopy(PRECOMPUTED_ROOK_MAGICS, 0, rookMagics, 0, rookMagics.length);
        Arrays.fill(squareMagicFound, true);
    }

    private void writeMagicNumbersToFile(ConcurrentHashMap<Integer, Long> magicNumbers) {
        File file = new File(ROOK_MAGIC_NUMBERS_PATH_write);
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

    // ----------------------- Perfect magic machinery -----------------------

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
            long atk = calculateRookMoves(square, occ);
            long cur = trial[idx];
            if (cur == EMPTY) trial[idx] = atk;
            else if (cur != atk) return false;
        }

        // Ensure in-memory table reflects this perfect mapping.
        rookAttacks[square] = trial;
        return true;
    }

    private long findPerfectMagicForSquare(int bits, long mask, long[] occs, long[] attacks, long[] tableOut) {
        final long EMPTY = Long.MIN_VALUE;
        long[] trial = new long[1 << bits];

        while (true) {
            long magic = rand64Sparse();
            // Reject weak candidates with poor dispersion in the high bits.
            if (Long.bitCount((mask * magic) >>> 56) < 6) continue;

            Arrays.fill(trial, EMPTY);
            boolean ok = true;
            for (int i = 0; i < occs.length; i++) {
                int idx = transformBits(occs[i], magic, bits);
                long atk = attacks[i];
                long cur = trial[idx];
                if (cur == EMPTY) trial[idx] = atk;
                else if (cur != atk) {
                    ok = false;
                    break;
                }
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
            for (long occ : occs) {
                int idx = transformBits(occ, 0L, bits);
                table[idx] = calculateRookMoves(square, occ);
            }
            return table;
        }
        for (long occ : occs) {
            int idx = transformBits(occ, magic, bits);
            table[idx] = calculateRookMoves(square, occ);
        }
        return table;
    }
}
