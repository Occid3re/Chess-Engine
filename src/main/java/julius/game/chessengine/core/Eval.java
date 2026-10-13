package julius.game.chessengine.core;

import static julius.game.chessengine.core.EvalParams.*;
import static julius.game.chessengine.core.Position.*;

/**
 * Tapered hand-crafted evaluation. Not thread-safe: use one instance per search thread.
 * When {@link #trace} is non-null every weight use is recorded as a coefficient (white perspective),
 * which lets the tuner treat the evaluation as a linear model of {@link EvalParams#W}.
 */
public final class Eval {

    private static final int ALL = 6;
    private static final int[] ATTACK_WEIGHT = {0, 2, 2, 3, 5, 0};

    public int[] trace;
    public int tracePhase;
    public int traceScale;

    private final long[][] attackedBy = new long[2][7];
    private final long[] attackedBy2 = new long[2];
    private final long[] kingZone = new long[2];
    private final int[] kingAttackUnits = new int[2];
    private final int[] kingAttackersCount = new int[2];
    private final long[] mobilityArea = new long[2];
    private final long[] passed = new long[2];

    private final PawnCache pawnCache;

    public Eval() {
        this(true);
    }

    public Eval(boolean usePawnCache) {
        pawnCache = usePawnCache ? new PawnCache(1 << 14) : null;
    }

    private int term(int color, int idx, int count) {
        if (trace != null) trace[idx] += color == WHITE ? count : -count;
        return color == WHITE ? W[idx] * count : -W[idx] * count;
    }

    private static int relRank(int sq, int color) {
        return color == WHITE ? (sq >> 3) : 7 - (sq >> 3);
    }

    /** Static evaluation from the side to move's perspective. */
    public int evaluate(Position p) {
        int v = evaluateWhite(p);
        return p.side == WHITE ? v : -v;
    }

    /** Static evaluation from white's perspective. */
    public int evaluateWhite(Position p) {
        long[] pc = p.pieces;
        for (int c = 0; c < 2; c++) {
            long pawnAtt = Attacks.pawnAttacksAll(pc[c * 6 + PAWN], c);
            int ksq = p.kingSquare(c);
            long kAtt = Attacks.KING[ksq];
            long[] ab = attackedBy[c];
            ab[PAWN] = pawnAtt;
            ab[KNIGHT] = ab[BISHOP] = ab[ROOK] = ab[QUEEN] = 0L;
            ab[KING] = kAtt;
            ab[ALL] = pawnAtt | kAtt;
            long pawns = pc[c * 6 + PAWN];
            long doublePawnAtt = c == WHITE
                    ? ((pawns & ~Attacks.FILE_A) << 7) & ((pawns & ~Attacks.FILE_H) << 9)
                    : ((pawns & ~Attacks.FILE_A) >>> 9) & ((pawns & ~Attacks.FILE_H) >>> 7);
            attackedBy2[c] = (pawnAtt & kAtt) | doublePawnAtt;
            kingZone[c] = kAtt | (1L << ksq);
            kingAttackUnits[c] = 0;
            kingAttackersCount[c] = 0;
        }
        for (int c = 0; c < 2; c++) {
            mobilityArea[c] = ~(pc[c * 6 + PAWN] | pc[c * 6 + KING]) & ~attackedBy[c ^ 1][PAWN];
        }

        int s = 0;
        // Material and piece-square tables
        for (int pt = PAWN; pt <= KING; pt++) {
            for (int c = 0; c < 2; c++) {
                long bb = pc[c * 6 + pt];
                while (bb != 0L) {
                    int sq = Long.numberOfTrailingZeros(bb);
                    bb &= bb - 1;
                    int rel = c == WHITE ? sq : sq ^ 56;
                    s += term(c, MATERIAL + pt, 1) + term(c, PST + pt * 64 + rel, 1);
                }
            }
        }

        s += pawnStructure(p);
        s += pieces(p, WHITE) + pieces(p, BLACK);
        s += king(p, WHITE) + king(p, BLACK);
        s += threats(p, WHITE) + threats(p, BLACK);
        s += passedPawns(p, WHITE) + passedPawns(p, BLACK);
        s += term(p.side, TEMPO, 1);

        int phase = Long.bitCount(p.typeBB(KNIGHT)) + Long.bitCount(p.typeBB(BISHOP))
                + 2 * Long.bitCount(p.typeBB(ROOK)) + 4 * Long.bitCount(p.typeBB(QUEEN));
        if (phase > 24) phase = 24;
        int mg = mg(s), eg = eg(s);
        int scale = scaleFactor(p, eg);
        if (trace != null) {
            tracePhase = phase;
            traceScale = scale;
        }
        return (mg * phase + eg * (24 - phase) * scale / 128) / 24;
    }

    // ------------------------------------------------------------------ pawns

    private int pawnStructure(Position p) {
        if (pawnCache != null && trace == null) {
            int idx = pawnCache.index(p.pawnKey);
            if (pawnCache.keys[idx] == p.pawnKey) {
                passed[WHITE] = pawnCache.passedWhite[idx];
                passed[BLACK] = pawnCache.passedBlack[idx];
                return pawnCache.scores[idx];
            }
            int s = pawns(p, WHITE) + pawns(p, BLACK) + shelter(p, WHITE) + shelter(p, BLACK);
            pawnCache.keys[idx] = p.pawnKey;
            pawnCache.scores[idx] = s;
            pawnCache.passedWhite[idx] = passed[WHITE];
            pawnCache.passedBlack[idx] = passed[BLACK];
            return s;
        }
        return pawns(p, WHITE) + pawns(p, BLACK) + shelter(p, WHITE) + shelter(p, BLACK);
    }

    private int pawns(Position p, int c) {
        int them = c ^ 1;
        long ours = p.pieces[c * 6 + PAWN];
        long theirs = p.pieces[them * 6 + PAWN];
        int s = 0;
        long passedBB = 0L;
        long bb = ours;
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            int f = sq & 7;
            int r = relRank(sq, c);
            boolean doubled = (Attacks.FORWARD_FILE[c][sq] & ours) != 0L;
            if (doubled) s += term(c, DOUBLED, 1);
            boolean isolated = (Attacks.ADJACENT_FILES[f] & ours) == 0L;
            long supporters = Attacks.PAWN[them][sq] & ours;
            long phalanx = ours & Attacks.ADJACENT_FILES[f] & Attacks.RANKS[sq >> 3];
            if (isolated) {
                s += term(c, ISOLATED, 1);
            } else {
                if (supporters != 0L) s += term(c, SUPPORTED + r, Long.bitCount(supporters));
                if (phalanx != 0L) s += term(c, PHALANX + r, 1);
                if (supporters == 0L && phalanx == 0L) {
                    int stop = c == WHITE ? sq + 8 : sq - 8;
                    boolean noHelpers = (ours & Attacks.ADJACENT_FILES[f] & ~Attacks.ATTACK_SPAN[c][sq]) == 0L;
                    if (noHelpers && (Attacks.PAWN[c][stop] & theirs) != 0L) s += term(c, BACKWARD, 1);
                }
            }
            if (!doubled && (Attacks.PASSED_SPAN[c][sq] & theirs) == 0L) passedBB |= 1L << sq;
        }
        passed[c] = passedBB;
        return s;
    }

    private int shelter(Position p, int c) {
        int them = c ^ 1;
        int ksq = p.kingSquare(c);
        int kf = ksq & 7;
        int kr = relRank(ksq, c);
        long ours = p.pieces[c * 6 + PAWN];
        long theirs = p.pieces[them * 6 + PAWN];
        int s = 0;
        int lo = Math.max(0, kf - 1), hi = Math.min(7, kf + 1);
        long front = Attacks.FORWARD_FILE[c][ksq];
        for (int f = lo; f <= hi; f++) {
            long fileMask = Attacks.FILES[f];
            // squares on this file at or in front of the king's rank
            long zone = fileMask & (shiftFileTo(front, kf, f) | (Attacks.RANKS[ksq >> 3] & fileMask));
            long o = ours & zone;
            int d = 0;
            if (o != 0L) {
                int psq = c == WHITE ? Long.numberOfTrailingZeros(o) : 63 - Long.numberOfLeadingZeros(o);
                d = Math.max(1, Math.min(7, relRank(psq, c) - kr));
            }
            s += term(c, SHELTER + d, 1);
            long t = theirs & zone;
            int e = 0;
            if (t != 0L) {
                int psq = c == WHITE ? Long.numberOfTrailingZeros(t) : 63 - Long.numberOfLeadingZeros(t);
                e = Math.max(1, Math.min(7, relRank(psq, c) - kr));
            }
            s += term(c, STORM + e, 1);
        }
        return s;
    }

    private static long shiftFileTo(long bb, int fromFile, int toFile) {
        int d = toFile - fromFile;
        return d >= 0 ? bb << d : bb >>> -d;
    }

    // ------------------------------------------------------------------ pieces

    private int pieces(Position p, int c) {
        int them = c ^ 1;
        long ours = p.pieces[c * 6 + PAWN];
        long allPawns = p.pieces[PAWN] | p.pieces[6 + PAWN];
        long theirPawns = p.pieces[them * 6 + PAWN];
        long occAll = p.all;
        long zone = kingZone[them];
        long mob = mobilityArea[c];
        long[] ab = attackedBy[c];
        int s = 0;

        long bb = p.pieces[c * 6 + KNIGHT];
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            long att = Attacks.KNIGHT[sq];
            attackedBy2[c] |= ab[ALL] & att;
            ab[ALL] |= att;
            ab[KNIGHT] |= att;
            s += term(c, MOB_KNIGHT + Long.bitCount(att & mob), 1);
            if ((att & zone) != 0L) {
                kingAttackersCount[c]++;
                kingAttackUnits[c] += ATTACK_WEIGHT[KNIGHT] + Long.bitCount(att & zone);
            }
            int r = relRank(sq, c);
            if (r >= 3 && r <= 5 && (ab[PAWN] & (1L << sq)) != 0L && (Attacks.ATTACK_SPAN[c][sq] & theirPawns) == 0L) {
                s += term(c, OUTPOST, 1);
            }
            int front = c == WHITE ? sq + 8 : sq - 8;
            if (front >= 0 && front < 64 && (allPawns & (1L << front)) != 0L) s += term(c, MINOR_BEHIND_PAWN, 1);
        }

        bb = p.pieces[c * 6 + BISHOP];
        if (Long.bitCount(bb) >= 2 && (bb & Attacks.LIGHT_SQUARES) != 0L && (bb & Attacks.DARK_SQUARES) != 0L) {
            s += term(c, BISHOP_PAIR, 1);
        }
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            long att = Attacks.bishop(sq, occAll ^ p.pieces[c * 6 + QUEEN]);
            attackedBy2[c] |= ab[ALL] & att;
            ab[ALL] |= att;
            ab[BISHOP] |= att;
            s += term(c, MOB_BISHOP + Long.bitCount(att & mob), 1);
            if ((att & zone) != 0L) {
                kingAttackersCount[c]++;
                kingAttackUnits[c] += ATTACK_WEIGHT[BISHOP] + Long.bitCount(att & zone);
            }
            int r = relRank(sq, c);
            if (r >= 3 && r <= 5 && (ab[PAWN] & (1L << sq)) != 0L && (Attacks.ATTACK_SPAN[c][sq] & theirPawns) == 0L) {
                s += term(c, OUTPOST + 1, 1);
            }
            int front = c == WHITE ? sq + 8 : sq - 8;
            if (front >= 0 && front < 64 && (allPawns & (1L << front)) != 0L) s += term(c, MINOR_BEHIND_PAWN, 1);
            long sameColor = ((1L << sq) & Attacks.LIGHT_SQUARES) != 0L ? Attacks.LIGHT_SQUARES : Attacks.DARK_SQUARES;
            s += term(c, BISHOP_PAWNS, Long.bitCount(ours & sameColor));
        }

        bb = p.pieces[c * 6 + ROOK];
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            long att = Attacks.rook(sq, occAll ^ p.pieces[c * 6 + QUEEN] ^ p.pieces[c * 6 + ROOK]);
            attackedBy2[c] |= ab[ALL] & att;
            ab[ALL] |= att;
            ab[ROOK] |= att;
            s += term(c, MOB_ROOK + Long.bitCount(att & mob), 1);
            if ((att & zone) != 0L) {
                kingAttackersCount[c]++;
                kingAttackUnits[c] += ATTACK_WEIGHT[ROOK] + Long.bitCount(att & zone);
            }
            long file = Attacks.FILES[sq & 7];
            if ((file & allPawns) == 0L) s += term(c, ROOK_OPEN, 1);
            else if ((file & ours) == 0L) s += term(c, ROOK_SEMI, 1);
        }

        bb = p.pieces[c * 6 + QUEEN];
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            long att = Attacks.queen(sq, occAll);
            attackedBy2[c] |= ab[ALL] & att;
            ab[ALL] |= att;
            ab[QUEEN] |= att;
            s += term(c, MOB_QUEEN + Long.bitCount(att & mob), 1);
            if ((att & zone) != 0L) {
                kingAttackersCount[c]++;
                kingAttackUnits[c] += ATTACK_WEIGHT[QUEEN] + Long.bitCount(att & zone);
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ king safety

    private int king(Position p, int c) {
        int them = c ^ 1;
        int ksq = p.kingSquare(c);
        int units = kingAttackUnits[them];
        if (kingAttackersCount[them] == 0) {
            return term(c, KING_DANGER, 1);
        }
        long[] ourAtt = attackedBy[c];
        long[] theirAtt = attackedBy[them];
        long weak = theirAtt[ALL] & ~attackedBy2[c] & (~ourAtt[ALL] | ourAtt[QUEEN] | ourAtt[KING]);
        units += Long.bitCount(kingZone[c] & weak);
        long safe = ~p.occ[them] & (~ourAtt[ALL] | (weak & attackedBy2[them]));
        long rookLines = Attacks.rook(ksq, p.all);
        long bishopLines = Attacks.bishop(ksq, p.all);
        if ((rookLines & safe & theirAtt[ROOK]) != 0L) units += 8;
        if (((rookLines | bishopLines) & safe & theirAtt[QUEEN]) != 0L) units += 6;
        if ((bishopLines & safe & theirAtt[BISHOP]) != 0L) units += 4;
        if ((Attacks.KNIGHT[ksq] & safe & theirAtt[KNIGHT]) != 0L) units += 6;
        if (p.pieces[them * 6 + QUEEN] == 0L) units /= 2;
        if (units > 39) units = 39;
        return term(c, KING_DANGER + units, 1);
    }

    // ------------------------------------------------------------------ threats

    private int threats(Position p, int c) {
        int them = c ^ 1;
        long[] ourAtt = attackedBy[c];
        long[] theirAtt = attackedBy[them];
        long theirKing = p.pieces[them * 6 + KING];
        long nonPawn = p.occ[them] & ~p.pieces[them * 6 + PAWN] & ~theirKing;
        int s = 0;

        long b = ourAtt[PAWN] & nonPawn;
        while (b != 0L) {
            int sq = Long.numberOfTrailingZeros(b);
            b &= b - 1;
            s += term(c, THREAT_BY_PAWN + type(p.board[sq]), 1);
        }
        b = (ourAtt[KNIGHT] | ourAtt[BISHOP]) & nonPawn;
        while (b != 0L) {
            int sq = Long.numberOfTrailingZeros(b);
            b &= b - 1;
            s += term(c, THREAT_BY_MINOR + type(p.board[sq]), 1);
        }
        b = ourAtt[ROOK] & nonPawn;
        while (b != 0L) {
            int sq = Long.numberOfTrailingZeros(b);
            b &= b - 1;
            s += term(c, THREAT_BY_ROOK + type(p.board[sq]), 1);
        }
        long hanging = p.occ[them] & ~theirKing & ourAtt[ALL] & ~theirAtt[ALL];
        if (hanging != 0L) s += term(c, HANGING, Long.bitCount(hanging));

        long ours = p.pieces[c * 6 + PAWN];
        long empty = ~p.all;
        long push;
        if (c == WHITE) {
            push = (ours << 8) & empty;
            push |= ((push & Attacks.RANK_3) << 8) & empty;
        } else {
            push = (ours >>> 8) & empty;
            push |= ((push & Attacks.RANK_6) >>> 8) & empty;
        }
        push &= ~theirAtt[PAWN] & (ourAtt[ALL] | ~theirAtt[ALL]);
        long pushThreats = Attacks.pawnAttacksAll(push, c) & nonPawn;
        if (pushThreats != 0L) s += term(c, PAWN_PUSH_THREAT, Long.bitCount(pushThreats));
        return s;
    }

    // ------------------------------------------------------------------ passed pawns

    private int passedPawns(Position p, int c) {
        long bb = passed[c];
        if (bb == 0L) return 0;
        int them = c ^ 1;
        int ourK = p.kingSquare(c), theirK = p.kingSquare(them);
        int s = 0;
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            int r = relRank(sq, c);
            s += term(c, PASSED + r, 1);
            int stop = c == WHITE ? sq + 8 : sq - 8;
            if (p.board[stop] != NO_PIECE) {
                s += term(c, PASSED_BLOCKED + r, 1);
            } else if ((attackedBy[them][ALL] & (1L << stop)) == 0L) {
                s += term(c, PASSED_FREE + r, 1);
            }
            s += term(c, PASSED_KDIST_OWN + r, Attacks.DISTANCE[ourK][stop]);
            s += term(c, PASSED_KDIST_THEIR + r, Attacks.DISTANCE[theirK][stop]);
        }
        return s;
    }

    // ------------------------------------------------------------------ scaling

    private static int nonPawnMaterial(Position p, int c) {
        int b = c * 6;
        return 320 * Long.bitCount(p.pieces[b + KNIGHT]) + 330 * Long.bitCount(p.pieces[b + BISHOP])
                + 500 * Long.bitCount(p.pieces[b + ROOK]) + 950 * Long.bitCount(p.pieces[b + QUEEN]);
    }

    private static int scaleFactor(Position p, int eg) {
        int strong = eg > 0 ? WHITE : BLACK;
        int weak = strong ^ 1;
        long strongPawns = p.pieces[strong * 6 + PAWN];
        int npmStrong = nonPawnMaterial(p, strong);
        int npmWeak = nonPawnMaterial(p, weak);
        if (strongPawns == 0L) {
            if (npmStrong - npmWeak <= 330) return npmStrong < 500 ? 0 : 16;
            if (npmStrong == 640 && Long.bitCount(p.pieces[strong * 6 + KNIGHT]) == 2 && npmWeak == 0) return 4;
        }
        long wb = p.pieces[BISHOP], bbish = p.pieces[6 + BISHOP];
        if (Long.bitCount(wb) == 1 && Long.bitCount(bbish) == 1) {
            boolean wLight = (wb & Attacks.LIGHT_SQUARES) != 0L;
            boolean bLight = (bbish & Attacks.LIGHT_SQUARES) != 0L;
            if (wLight != bLight) {
                if (npmStrong == 330 && npmWeak == 330) return 64;
                return 104;
            }
        }
        if (Long.bitCount(strongPawns) == 1 && npmStrong <= npmWeak + 100 && npmWeak > 0) return 80;
        return 128;
    }

    /** Small direct-mapped cache for the pawn-structure and shelter terms (keyed by pawns + kings). */
    static final class PawnCache {
        final long[] keys;
        final int[] scores;
        final long[] passedWhite;
        final long[] passedBlack;
        final int mask;

        PawnCache(int size) {
            keys = new long[size];
            scores = new int[size];
            passedWhite = new long[size];
            passedBlack = new long[size];
            mask = size - 1;
            java.util.Arrays.fill(keys, -1L);
        }

        int index(long key) {
            return (int) (key ^ (key >>> 32)) & mask;
        }
    }
}
