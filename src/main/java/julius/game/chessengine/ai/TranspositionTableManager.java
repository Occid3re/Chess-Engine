package julius.game.chessengine.ai;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Manages the lifecycle and sizing of the main and capture transposition tables.
 * Extracted from AI to centralise hash-budget arithmetic and table construction.
 */
@Log4j2
final class TranspositionTableManager {

    private static final int MAIN_TT_ENTRY_BYTES = 48;
    private static final int CAPTURE_TT_ENTRY_BYTES = 32;

    static final int MIN_MAIN_TT_ENTRIES = 1 << 12;
    static final int MAX_MAIN_TT_ENTRIES = 1 << 26;
    static final int MIN_CAPTURE_TT_ENTRIES = 1 << 11;
    static final int MAX_CAPTURE_TT_ENTRIES = 1 << 25;

    static final int MIN_HASH_SIZE_MB = 1;
    static final int MAX_HASH_SIZE_MB = 4096;

    @Getter
    private TranspositionTable<TranspositionTableEntry> mainTable;

    @Getter
    private int mainTableCapacity;

    @Getter
    private TranspositionTable<CaptureTranspositionTableEntry> captureTable;

    @Getter
    private int captureTableCapacity;

    @Getter
    private int hashSizeMb;

    private final double ttMainWeight;
    private final double ttCaptureWeight;

    TranspositionTableManager(int hashSizeMb, double ttMainWeight, double ttCaptureWeight) {
        this.hashSizeMb = Math.max(MIN_HASH_SIZE_MB, Math.min(hashSizeMb, MAX_HASH_SIZE_MB));
        this.ttMainWeight = Math.max(1e-9, ttMainWeight);
        this.ttCaptureWeight = Math.max(1e-9, ttCaptureWeight);
    }

    void rebuild(boolean concurrent) {
        long totalBytes = Math.max(1L, (long) hashSizeMb * 1024L * 1024L);

        double totalWeight = ttMainWeight + ttCaptureWeight;
        if (totalWeight <= 0.0) {
            totalWeight = 1.0;
        }
        long mainBudget = Math.max(1L, (long) (totalBytes * (ttMainWeight / totalWeight)));
        long captureBudget = Math.max(1L, totalBytes - mainBudget);

        int mainCap = computeTableCapacity(mainBudget, MAIN_TT_ENTRY_BYTES,
                MIN_MAIN_TT_ENTRIES, MAX_MAIN_TT_ENTRIES);
        int captureCap = computeTableCapacity(captureBudget, CAPTURE_TT_ENTRY_BYTES,
                MIN_CAPTURE_TT_ENTRIES, MAX_CAPTURE_TT_ENTRIES);

        this.mainTableCapacity = mainCap;
        this.captureTableCapacity = captureCap;

        this.mainTable = concurrent
                ? new FixedSizeTranspositionTable<>(mainCap)
                : new PlainFixedSizeTranspositionTable<>(mainCap, TranspositionTableEntry.class);

        this.captureTable = concurrent
                ? new FixedSizeTranspositionTable<>(captureCap)
                : new PlainFixedSizeTranspositionTable<>(captureCap, CaptureTranspositionTableEntry.class);
    }

    void setHashSizeMb(int mb) {
        int clamped = Math.max(MIN_HASH_SIZE_MB, Math.min(mb, MAX_HASH_SIZE_MB));
        if (clamped == this.hashSizeMb) {
            return;
        }

        if (mainTable != null) {
            mainTable.clear();
        }
        if (captureTable != null) {
            captureTable.clear();
        }

        this.hashSizeMb = clamped;
    }

    static int computeTableCapacity(long budgetBytes, int entryBytes, int minEntries, int maxEntries) {
        if (entryBytes <= 0) {
            throw new IllegalArgumentException("Entry byte estimate must be positive");
        }

        long estimatedEntries = Math.max(1L, budgetBytes / entryBytes);
        if (estimatedEntries > Integer.MAX_VALUE) {
            estimatedEntries = Integer.MAX_VALUE;
        }

        int candidate = (int) estimatedEntries;
        if (candidate < minEntries) {
            candidate = minEntries;
        } else if (candidate > maxEntries) {
            candidate = maxEntries;
        }

        int rounded = roundUpToPowerOfTwo(candidate);
        if (rounded < minEntries) {
            rounded = minEntries;
        }
        while (rounded > maxEntries && rounded > 1) {
            rounded >>= 1;
        }
        return rounded;
    }

    static int roundUpToPowerOfTwo(int value) {
        if (value <= 1) return 1;
        if (value > (1 << 30)) return 1 << 30;
        int hib = Integer.highestOneBit(value);
        if (hib == value) return value;
        return hib << 1;
    }
}
