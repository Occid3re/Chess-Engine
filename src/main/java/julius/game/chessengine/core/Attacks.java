package julius.game.chessengine.core;

/**
 * Precomputed attack tables. Squares are numbered a1=0 .. h8=63.
 * Slider attacks use fancy magic bitboards whose magics are found at class-load time
 * with a fixed seed, so the tables are deterministic.
 */
public final class Attacks {

    public static final long FILE_A = 0x0101010101010101L;
    public static final long FILE_H = FILE_A << 7;
    public static final long RANK_1 = 0xFFL;
    public static final long RANK_2 = RANK_1 << 8;
    public static final long RANK_3 = RANK_1 << 16;
    public static final long RANK_4 = RANK_1 << 24;
    public static final long RANK_5 = RANK_1 << 32;
    public static final long RANK_6 = RANK_1 << 40;
    public static final long RANK_7 = RANK_1 << 48;
    public static final long RANK_8 = RANK_1 << 56;
    public static final long LIGHT_SQUARES = 0x55AA55AA55AA55AAL;
    public static final long DARK_SQUARES = ~LIGHT_SQUARES;

    public static final long[] KNIGHT = new long[64];
    public static final long[] KING = new long[64];
    /** PAWN[color][sq] = squares attacked by a pawn of that color standing on sq. */
    public static final long[][] PAWN = new long[2][64];
    /** Squares strictly between two aligned squares (0 if not aligned). */
    public static final long[][] BETWEEN = new long[64][64];
    /** Full line through two aligned squares, edge to edge (0 if not aligned). */
    public static final long[][] LINE = new long[64][64];
    public static final long[] FILES = new long[8];
    public static final long[] RANKS = new long[8];
    public static final long[] ADJACENT_FILES = new long[8];
    /** Squares in front of sq (same file) from the perspective of color. */
    public static final long[][] FORWARD_FILE = new long[2][64];
    /** Squares in front of sq on the same and adjacent files (passed-pawn span). */
    public static final long[][] PASSED_SPAN = new long[2][64];
    /** Squares in front of sq on adjacent files (where enemy pawns could attack the pawn's path). */
    public static final long[][] ATTACK_SPAN = new long[2][64];
    public static final int[][] DISTANCE = new int[64][64];

    private static final long[] ROOK_MASK = new long[64];
    private static final long[] BISHOP_MASK = new long[64];
    private static final long[] ROOK_MAGIC = new long[64];
    private static final long[] BISHOP_MAGIC = new long[64];
    private static final int[] ROOK_SHIFT = new int[64];
    private static final int[] BISHOP_SHIFT = new int[64];
    private static final int[] ROOK_OFFSET = new int[64];
    private static final int[] BISHOP_OFFSET = new int[64];
    private static final long[] ROOK_TABLE;
    private static final long[] BISHOP_TABLE;

    private static final int[][] ROOK_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private Attacks() {
    }

    static {
        for (int i = 0; i < 8; i++) {
            FILES[i] = FILE_A << i;
            RANKS[i] = RANK_1 << (8 * i);
        }
        for (int i = 0; i < 8; i++) {
            ADJACENT_FILES[i] = (i > 0 ? FILES[i - 1] : 0L) | (i < 7 ? FILES[i + 1] : 0L);
        }
        for (int sq = 0; sq < 64; sq++) {
            int r = sq >> 3, f = sq & 7;
            KNIGHT[sq] = leaper(r, f, new int[][]{{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}});
            KING[sq] = leaper(r, f, new int[][]{{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}});
            PAWN[0][sq] = leaper(r, f, new int[][]{{1, -1}, {1, 1}});
            PAWN[1][sq] = leaper(r, f, new int[][]{{-1, -1}, {-1, 1}});
            for (int s2 = 0; s2 < 64; s2++) {
                DISTANCE[sq][s2] = Math.max(Math.abs(r - (s2 >> 3)), Math.abs(f - (s2 & 7)));
            }
            long fwdW = 0L, fwdB = 0L;
            for (int rr = r + 1; rr < 8; rr++) fwdW |= 1L << (rr * 8 + f);
            for (int rr = r - 1; rr >= 0; rr--) fwdB |= 1L << (rr * 8 + f);
            FORWARD_FILE[0][sq] = fwdW;
            FORWARD_FILE[1][sq] = fwdB;
            long adjW = 0L, adjB = 0L;
            if (f > 0) {
                adjW |= fwdW >>> 1;
                adjB |= fwdB >>> 1;
            }
            if (f < 7) {
                adjW |= fwdW << 1;
                adjB |= fwdB << 1;
            }
            ATTACK_SPAN[0][sq] = adjW;
            ATTACK_SPAN[1][sq] = adjB;
            PASSED_SPAN[0][sq] = adjW | fwdW;
            PASSED_SPAN[1][sq] = adjB | fwdB;
        }

        long edgesFiles = FILE_A | FILE_H;
        long edgesRanks = RANK_1 | RANK_8;
        int rookSize = 0, bishopSize = 0;
        for (int sq = 0; sq < 64; sq++) {
            long fileOfSq = FILE_A << (sq & 7);
            long rankOfSq = RANK_1 << (8 * (sq >> 3));
            ROOK_MASK[sq] = slidingAttack(sq, 0L, ROOK_DIRS)
                    & ~((edgesRanks & ~rankOfSq) | (edgesFiles & ~fileOfSq));
            BISHOP_MASK[sq] = slidingAttack(sq, 0L, BISHOP_DIRS) & ~(edgesRanks | edgesFiles);
            ROOK_SHIFT[sq] = 64 - Long.bitCount(ROOK_MASK[sq]);
            BISHOP_SHIFT[sq] = 64 - Long.bitCount(BISHOP_MASK[sq]);
            ROOK_OFFSET[sq] = rookSize;
            BISHOP_OFFSET[sq] = bishopSize;
            rookSize += 1 << Long.bitCount(ROOK_MASK[sq]);
            bishopSize += 1 << Long.bitCount(BISHOP_MASK[sq]);
        }
        ROOK_TABLE = new long[rookSize];
        BISHOP_TABLE = new long[bishopSize];
        long[] seed = {0x9E3779B97F4A7C15L};
        for (int sq = 0; sq < 64; sq++) {
            ROOK_MAGIC[sq] = findMagic(sq, ROOK_MASK[sq], ROOK_SHIFT[sq], ROOK_DIRS, ROOK_TABLE, ROOK_OFFSET[sq], seed);
            BISHOP_MAGIC[sq] = findMagic(sq, BISHOP_MASK[sq], BISHOP_SHIFT[sq], BISHOP_DIRS, BISHOP_TABLE, BISHOP_OFFSET[sq], seed);
        }

        for (int a = 0; a < 64; a++) {
            for (int b = 0; b < 64; b++) {
                if (a == b) continue;
                long bb = 1L << b;
                if ((slidingAttack(a, 0L, ROOK_DIRS) & bb) != 0) {
                    BETWEEN[a][b] = slidingAttack(a, bb, ROOK_DIRS) & slidingAttack(b, 1L << a, ROOK_DIRS);
                    LINE[a][b] = (slidingAttack(a, 0L, ROOK_DIRS) & slidingAttack(b, 0L, ROOK_DIRS)) | (1L << a) | bb;
                } else if ((slidingAttack(a, 0L, BISHOP_DIRS) & bb) != 0) {
                    BETWEEN[a][b] = slidingAttack(a, bb, BISHOP_DIRS) & slidingAttack(b, 1L << a, BISHOP_DIRS);
                    LINE[a][b] = (slidingAttack(a, 0L, BISHOP_DIRS) & slidingAttack(b, 0L, BISHOP_DIRS)) | (1L << a) | bb;
                }
            }
        }
    }

    private static long leaper(int r, int f, int[][] deltas) {
        long bb = 0L;
        for (int[] d : deltas) {
            int rr = r + d[0], ff = f + d[1];
            if (rr >= 0 && rr < 8 && ff >= 0 && ff < 8) bb |= 1L << (rr * 8 + ff);
        }
        return bb;
    }

    private static long slidingAttack(int sq, long occ, int[][] dirs) {
        long att = 0L;
        int r0 = sq >> 3, f0 = sq & 7;
        for (int[] d : dirs) {
            int r = r0 + d[0], f = f0 + d[1];
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                long b = 1L << (r * 8 + f);
                att |= b;
                if ((occ & b) != 0) break;
                r += d[0];
                f += d[1];
            }
        }
        return att;
    }

    private static long nextRandom(long[] s) {
        long x = s[0];
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        s[0] = x;
        return x * 2685821657736338717L;
    }

    private static long findMagic(int sq, long mask, int shift, int[][] dirs, long[] table, int offset, long[] seed) {
        int bits = Long.bitCount(mask);
        int n = 1 << bits;
        long[] occs = new long[n];
        long[] atts = new long[n];
        long sub = 0L;
        for (int i = 0; i < n; i++) {
            occs[i] = sub;
            atts[i] = slidingAttack(sq, sub, dirs);
            sub = (sub - mask) & mask;
        }
        int[] epoch = new int[n];
        int attempt = 0;
        while (true) {
            long magic = nextRandom(seed) & nextRandom(seed) & nextRandom(seed);
            if (Long.bitCount((mask * magic) >>> 56) < 6) continue;
            attempt++;
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                int idx = (int) ((occs[i] * magic) >>> shift);
                if (epoch[idx] != attempt) {
                    epoch[idx] = attempt;
                    table[offset + idx] = atts[i];
                } else if (table[offset + idx] != atts[i]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return magic;
        }
    }

    public static long rook(int sq, long occ) {
        return ROOK_TABLE[ROOK_OFFSET[sq] + (int) (((occ & ROOK_MASK[sq]) * ROOK_MAGIC[sq]) >>> ROOK_SHIFT[sq])];
    }

    public static long bishop(int sq, long occ) {
        return BISHOP_TABLE[BISHOP_OFFSET[sq] + (int) (((occ & BISHOP_MASK[sq]) * BISHOP_MAGIC[sq]) >>> BISHOP_SHIFT[sq])];
    }

    public static long queen(int sq, long occ) {
        return rook(sq, occ) | bishop(sq, occ);
    }

    /** Attacks of a non-pawn piece type (KNIGHT..KING as defined in {@link Position}). */
    public static long pieceAttacks(int type, int sq, long occ) {
        return switch (type) {
            case Position.KNIGHT -> KNIGHT[sq];
            case Position.BISHOP -> bishop(sq, occ);
            case Position.ROOK -> rook(sq, occ);
            case Position.QUEEN -> queen(sq, occ);
            case Position.KING -> KING[sq];
            default -> 0L;
        };
    }

    public static long pawnAttacksAll(long pawns, int color) {
        if (color == Position.WHITE) {
            return ((pawns & ~FILE_A) << 7) | ((pawns & ~FILE_H) << 9);
        }
        return ((pawns & ~FILE_A) >>> 9) | ((pawns & ~FILE_H) >>> 7);
    }
}
