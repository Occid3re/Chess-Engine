package julius.game.chessengine.core;

/**
 * Mutable board state with incremental Zobrist hashing and an allocation-free make/unmake stack.
 * Pieces are encoded as color * 6 + type; empty squares hold {@link #NO_PIECE}.
 */
public final class Position {

    public static final int WHITE = 0, BLACK = 1;
    public static final int PAWN = 0, KNIGHT = 1, BISHOP = 2, ROOK = 3, QUEEN = 4, KING = 5;
    public static final int NO_PIECE = -1;

    public static final int WK_CASTLE = 1, WQ_CASTLE = 2, BK_CASTLE = 4, BQ_CASTLE = 8;

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /** Material values used by SEE and move ordering (not by the evaluation). */
    public static final int[] SEE_VALUE = {100, 320, 330, 500, 950, 0};

    private static final int MAX_HISTORY = 4096;

    static final long[][] PIECE_KEYS = new long[12][64];
    static final long[] CASTLE_KEYS = new long[16];
    static final long[] EP_KEYS = new long[8];
    static final long SIDE_KEY;
    private static final int[] CASTLE_MASK = new int[64];

    static {
        long[] s = {0x2545F4914F6CDD1DL};
        for (int p = 0; p < 12; p++) {
            for (int sq = 0; sq < 64; sq++) PIECE_KEYS[p][sq] = rnd(s);
        }
        for (int i = 0; i < 16; i++) CASTLE_KEYS[i] = rnd(s);
        for (int i = 0; i < 8; i++) EP_KEYS[i] = rnd(s);
        SIDE_KEY = rnd(s);
        for (int i = 0; i < 64; i++) CASTLE_MASK[i] = 15;
        CASTLE_MASK[0] &= ~WQ_CASTLE;
        CASTLE_MASK[7] &= ~WK_CASTLE;
        CASTLE_MASK[4] &= ~(WK_CASTLE | WQ_CASTLE);
        CASTLE_MASK[56] &= ~BQ_CASTLE;
        CASTLE_MASK[63] &= ~BK_CASTLE;
        CASTLE_MASK[60] &= ~(BK_CASTLE | BQ_CASTLE);
    }

    private static long rnd(long[] s) {
        long z = (s[0] += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public final long[] pieces = new long[12];
    public final long[] occ = new long[2];
    public long all;
    public final int[] board = new int[64];
    public int side;
    public int castling;
    public int ep = -1;
    public int halfmove;
    public int fullmove = 1;
    public int pliesFromNull;
    public long key;
    public long pawnKey;
    public long checkers;

    private long pinned;
    private boolean pinnedValid;

    // Undo stack
    private int histPly;
    private final long[] keyHist = new long[MAX_HISTORY];
    private final long[] pawnKeyHist = new long[MAX_HISTORY];
    private final long[] checkersHist = new long[MAX_HISTORY];
    private final int[] capturedHist = new int[MAX_HISTORY];
    private final int[] castlingHist = new int[MAX_HISTORY];
    private final int[] epHist = new int[MAX_HISTORY];
    private final int[] halfmoveHist = new int[MAX_HISTORY];
    private final int[] nullHist = new int[MAX_HISTORY];

    public Position() {
        setFen(START_FEN);
    }

    public Position(String fen) {
        setFen(fen);
    }

    public Position(Position other) {
        copyFrom(other);
    }

    public void copyFrom(Position o) {
        System.arraycopy(o.pieces, 0, pieces, 0, 12);
        occ[0] = o.occ[0];
        occ[1] = o.occ[1];
        all = o.all;
        System.arraycopy(o.board, 0, board, 0, 64);
        side = o.side;
        castling = o.castling;
        ep = o.ep;
        halfmove = o.halfmove;
        fullmove = o.fullmove;
        pliesFromNull = o.pliesFromNull;
        key = o.key;
        pawnKey = o.pawnKey;
        checkers = o.checkers;
        pinnedValid = false;
        histPly = o.histPly;
        System.arraycopy(o.keyHist, 0, keyHist, 0, histPly);
        System.arraycopy(o.pawnKeyHist, 0, pawnKeyHist, 0, histPly);
        System.arraycopy(o.checkersHist, 0, checkersHist, 0, histPly);
        System.arraycopy(o.capturedHist, 0, capturedHist, 0, histPly);
        System.arraycopy(o.castlingHist, 0, castlingHist, 0, histPly);
        System.arraycopy(o.epHist, 0, epHist, 0, histPly);
        System.arraycopy(o.halfmoveHist, 0, halfmoveHist, 0, histPly);
        System.arraycopy(o.nullHist, 0, nullHist, 0, histPly);
    }

    public static int color(int piece) {
        return piece >= 6 ? BLACK : WHITE;
    }

    public static int type(int piece) {
        return piece >= 6 ? piece - 6 : piece;
    }

    public static int piece(int color, int type) {
        return color * 6 + type;
    }

    public long pieces(int color, int type) {
        return pieces[color * 6 + type];
    }

    public long typeBB(int type) {
        return pieces[type] | pieces[6 + type];
    }

    public int kingSquare(int color) {
        return Long.numberOfTrailingZeros(pieces[color * 6 + KING]);
    }

    public boolean inCheck() {
        return checkers != 0L;
    }

    public int pieceCount() {
        return Long.bitCount(all);
    }

    /** True if the color has any piece other than pawns and king. */
    public boolean hasNonPawnMaterial(int color) {
        return (occ[color] & ~pieces[color * 6 + PAWN] & ~pieces[color * 6 + KING]) != 0L;
    }

    // ------------------------------------------------------------------ FEN

    public void setFen(String fen) {
        String[] parts = fen.trim().split("\\s+");
        java.util.Arrays.fill(pieces, 0L);
        occ[0] = occ[1] = all = 0L;
        java.util.Arrays.fill(board, NO_PIECE);
        key = 0L;
        pawnKey = 0L;
        int sq = 56;
        for (char c : parts[0].toCharArray()) {
            if (c == '/') {
                sq -= 16;
            } else if (Character.isDigit(c)) {
                sq += c - '0';
            } else {
                int idx = "PNBRQKpnbrqk".indexOf(c);
                if (idx < 0) throw new IllegalArgumentException("Bad FEN piece: " + c);
                addPiece(idx, sq);
                sq++;
            }
        }
        side = parts.length > 1 && parts[1].equals("b") ? BLACK : WHITE;
        castling = 0;
        if (parts.length > 2) {
            for (char c : parts[2].toCharArray()) {
                switch (c) {
                    case 'K' -> castling |= WK_CASTLE;
                    case 'Q' -> castling |= WQ_CASTLE;
                    case 'k' -> castling |= BK_CASTLE;
                    case 'q' -> castling |= BQ_CASTLE;
                    default -> {
                    }
                }
            }
        }
        // Drop castling rights that are inconsistent with the piece placement.
        if (board[4] != piece(WHITE, KING)) castling &= ~(WK_CASTLE | WQ_CASTLE);
        if (board[7] != piece(WHITE, ROOK)) castling &= ~WK_CASTLE;
        if (board[0] != piece(WHITE, ROOK)) castling &= ~WQ_CASTLE;
        if (board[60] != piece(BLACK, KING)) castling &= ~(BK_CASTLE | BQ_CASTLE);
        if (board[63] != piece(BLACK, ROOK)) castling &= ~BK_CASTLE;
        if (board[56] != piece(BLACK, ROOK)) castling &= ~BQ_CASTLE;
        ep = -1;
        if (parts.length > 3 && !parts[3].equals("-")) {
            int epSq = Move.parseSquare(parts[3]);
            // Only keep the en-passant square if a capture is actually possible.
            if ((Attacks.PAWN[side ^ 1][epSq] & pieces(side, PAWN)) != 0L) ep = epSq;
        }
        halfmove = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        fullmove = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        pliesFromNull = halfmove;
        key ^= CASTLE_KEYS[castling];
        if (ep >= 0) key ^= EP_KEYS[ep & 7];
        if (side == BLACK) key ^= SIDE_KEY;
        histPly = 0;
        checkers = attackersTo(kingSquare(side), all) & occ[side ^ 1];
        pinnedValid = false;
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                int p = board[r * 8 + f];
                if (p == NO_PIECE) {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append("PNBRQKpnbrqk".charAt(p));
                }
            }
            if (empty > 0) sb.append(empty);
            if (r > 0) sb.append('/');
        }
        sb.append(side == WHITE ? " w " : " b ");
        if (castling == 0) sb.append('-');
        if ((castling & WK_CASTLE) != 0) sb.append('K');
        if ((castling & WQ_CASTLE) != 0) sb.append('Q');
        if ((castling & BK_CASTLE) != 0) sb.append('k');
        if ((castling & BQ_CASTLE) != 0) sb.append('q');
        sb.append(' ').append(ep >= 0 ? Move.squareName(ep) : "-");
        sb.append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    // ------------------------------------------------------------------ piece updates

    private void addPiece(int pc, int sq) {
        long b = 1L << sq;
        pieces[pc] |= b;
        occ[color(pc)] |= b;
        all |= b;
        board[sq] = pc;
        key ^= PIECE_KEYS[pc][sq];
        if (type(pc) == PAWN || type(pc) == KING) pawnKey ^= PIECE_KEYS[pc][sq];
    }

    private void removePiece(int sq) {
        int pc = board[sq];
        long b = 1L << sq;
        pieces[pc] ^= b;
        occ[color(pc)] ^= b;
        all ^= b;
        board[sq] = NO_PIECE;
        key ^= PIECE_KEYS[pc][sq];
        if (type(pc) == PAWN || type(pc) == KING) pawnKey ^= PIECE_KEYS[pc][sq];
    }

    private void movePiece(int from, int to) {
        int pc = board[from];
        long b = (1L << from) | (1L << to);
        pieces[pc] ^= b;
        occ[color(pc)] ^= b;
        all ^= b;
        board[from] = NO_PIECE;
        board[to] = pc;
        long k = PIECE_KEYS[pc][from] ^ PIECE_KEYS[pc][to];
        key ^= k;
        if (type(pc) == PAWN || type(pc) == KING) pawnKey ^= k;
    }

    // Unmake helpers do not touch the keys; keys are restored from the history stack.
    private void addPieceRaw(int pc, int sq) {
        long b = 1L << sq;
        pieces[pc] |= b;
        occ[color(pc)] |= b;
        all |= b;
        board[sq] = pc;
    }

    private void removePieceRaw(int sq) {
        int pc = board[sq];
        long b = 1L << sq;
        pieces[pc] ^= b;
        occ[color(pc)] ^= b;
        all ^= b;
        board[sq] = NO_PIECE;
    }

    private void movePieceRaw(int from, int to) {
        int pc = board[from];
        long b = (1L << from) | (1L << to);
        pieces[pc] ^= b;
        occ[color(pc)] ^= b;
        all ^= b;
        board[from] = NO_PIECE;
        board[to] = pc;
    }

    // ------------------------------------------------------------------ make / unmake

    public void makeMove(int move) {
        int from = Move.from(move), to = Move.to(move), flag = Move.flag(move);
        int us = side, them = us ^ 1;
        int h = histPly++;
        keyHist[h] = key;
        pawnKeyHist[h] = pawnKey;
        checkersHist[h] = checkers;
        castlingHist[h] = castling;
        epHist[h] = ep;
        halfmoveHist[h] = halfmove;
        nullHist[h] = pliesFromNull;

        key ^= SIDE_KEY;
        if (ep >= 0) {
            key ^= EP_KEYS[ep & 7];
            ep = -1;
        }
        halfmove++;
        pliesFromNull++;

        int captured = NO_PIECE;
        int pc = board[from];
        if (flag == Move.EP_CAPTURE) {
            int capSq = to ^ 8;
            captured = board[capSq];
            removePiece(capSq);
        } else if ((flag & Move.CAPTURE) != 0) {
            captured = board[to];
            removePiece(to);
        }
        capturedHist[h] = captured;
        if (captured != NO_PIECE) halfmove = 0;

        movePiece(from, to);
        if (type(pc) == PAWN) {
            halfmove = 0;
            if (flag == Move.DOUBLE_PUSH) {
                int epSq = (from + to) >>> 1;
                if ((Attacks.PAWN[us][epSq] & pieces[them * 6 + PAWN]) != 0L) {
                    ep = epSq;
                    key ^= EP_KEYS[epSq & 7];
                }
            } else if ((flag & Move.PROMO_N) != 0) {
                removePiece(to);
                addPiece(piece(us, KNIGHT + (flag & 3)), to);
            }
        } else if (flag == Move.KING_CASTLE) {
            movePiece(to + 1, to - 1);
        } else if (flag == Move.QUEEN_CASTLE) {
            movePiece(to - 2, to + 1);
        }

        int newCastling = castling & CASTLE_MASK[from] & CASTLE_MASK[to];
        if (newCastling != castling) {
            key ^= CASTLE_KEYS[castling] ^ CASTLE_KEYS[newCastling];
            castling = newCastling;
        }

        side = them;
        if (us == BLACK) fullmove++;
        checkers = attackersTo(kingSquare(them), all) & occ[us];
        pinnedValid = false;
    }

    public void unmakeMove(int move) {
        int from = Move.from(move), to = Move.to(move), flag = Move.flag(move);
        int h = --histPly;
        side ^= 1;
        int us = side;
        if (us == BLACK) fullmove--;

        if ((flag & Move.PROMO_N) != 0) {
            removePieceRaw(to);
            addPieceRaw(piece(us, PAWN), to);
        }
        movePieceRaw(to, from);
        if (flag == Move.KING_CASTLE) {
            movePieceRaw(to - 1, to + 1);
        } else if (flag == Move.QUEEN_CASTLE) {
            movePieceRaw(to + 1, to - 2);
        }
        int captured = capturedHist[h];
        if (captured != NO_PIECE) {
            addPieceRaw(captured, flag == Move.EP_CAPTURE ? (to ^ 8) : to);
        }

        key = keyHist[h];
        pawnKey = pawnKeyHist[h];
        checkers = checkersHist[h];
        castling = castlingHist[h];
        ep = epHist[h];
        halfmove = halfmoveHist[h];
        pliesFromNull = nullHist[h];
        pinnedValid = false;
    }

    public void makeNull() {
        int h = histPly++;
        keyHist[h] = key;
        pawnKeyHist[h] = pawnKey;
        checkersHist[h] = checkers;
        castlingHist[h] = castling;
        epHist[h] = ep;
        halfmoveHist[h] = halfmove;
        nullHist[h] = pliesFromNull;
        capturedHist[h] = NO_PIECE;
        key ^= SIDE_KEY;
        if (ep >= 0) {
            key ^= EP_KEYS[ep & 7];
            ep = -1;
        }
        halfmove++;
        pliesFromNull = 0;
        side ^= 1;
        checkers = 0L;
        pinnedValid = false;
    }

    public void unmakeNull() {
        int h = --histPly;
        side ^= 1;
        key = keyHist[h];
        pawnKey = pawnKeyHist[h];
        checkers = checkersHist[h];
        castling = castlingHist[h];
        ep = epHist[h];
        halfmove = halfmoveHist[h];
        pliesFromNull = nullHist[h];
        pinnedValid = false;
    }

    /** Captured piece of the most recent move (NO_PIECE if none). */
    public int lastCaptured() {
        return histPly > 0 ? capturedHist[histPly - 1] : NO_PIECE;
    }

    // ------------------------------------------------------------------ attacks

    public long attackersTo(int sq, long occupied) {
        return (Attacks.PAWN[BLACK][sq] & pieces[PAWN])
                | (Attacks.PAWN[WHITE][sq] & pieces[6 + PAWN])
                | (Attacks.KNIGHT[sq] & (pieces[KNIGHT] | pieces[6 + KNIGHT]))
                | (Attacks.bishop(sq, occupied) & (pieces[BISHOP] | pieces[6 + BISHOP] | pieces[QUEEN] | pieces[6 + QUEEN]))
                | (Attacks.rook(sq, occupied) & (pieces[ROOK] | pieces[6 + ROOK] | pieces[QUEEN] | pieces[6 + QUEEN]))
                | (Attacks.KING[sq] & (pieces[KING] | pieces[6 + KING]));
    }

    public boolean isAttacked(int sq, int byColor, long occupied) {
        int b = byColor * 6;
        if ((Attacks.PAWN[byColor ^ 1][sq] & pieces[b + PAWN]) != 0L) return true;
        if ((Attacks.KNIGHT[sq] & pieces[b + KNIGHT]) != 0L) return true;
        if ((Attacks.KING[sq] & pieces[b + KING]) != 0L) return true;
        long bq = pieces[b + BISHOP] | pieces[b + QUEEN];
        if (bq != 0L && (Attacks.bishop(sq, occupied) & bq) != 0L) return true;
        long rq = pieces[b + ROOK] | pieces[b + QUEEN];
        return rq != 0L && (Attacks.rook(sq, occupied) & rq) != 0L;
    }

    /** Pieces of the side to move that are pinned to their own king. */
    public long pinned() {
        if (!pinnedValid) {
            pinned = computeBlockers(side);
            pinnedValid = true;
        }
        return pinned;
    }

    /** Pieces (of either color) shielding the king of {@code color} from enemy sliders. */
    public long computeBlockers(int color) {
        int ksq = kingSquare(color);
        int them = color ^ 1;
        long snipers = (Attacks.rook(ksq, 0L) & (pieces[them * 6 + ROOK] | pieces[them * 6 + QUEEN]))
                | (Attacks.bishop(ksq, 0L) & (pieces[them * 6 + BISHOP] | pieces[them * 6 + QUEEN]));
        long result = 0L;
        while (snipers != 0L) {
            int s = Long.numberOfTrailingZeros(snipers);
            snipers &= snipers - 1;
            long b = Attacks.BETWEEN[ksq][s] & all;
            if (b != 0L && (b & (b - 1)) == 0L) result |= b & occ[color];
        }
        return result;
    }

    // ------------------------------------------------------------------ legality

    /** Full legality check for a pseudo-legal move of the side to move. */
    public boolean isLegal(int move) {
        int from = Move.from(move), to = Move.to(move), flag = Move.flag(move);
        int us = side, them = us ^ 1;
        int ksq = kingSquare(us);
        if (flag == Move.EP_CAPTURE) {
            int capSq = to ^ 8;
            long occ2 = (all ^ (1L << from) ^ (1L << capSq)) | (1L << to);
            int t = them * 6;
            return (Attacks.rook(ksq, occ2) & (pieces[t + ROOK] | pieces[t + QUEEN])) == 0L
                    && (Attacks.bishop(ksq, occ2) & (pieces[t + BISHOP] | pieces[t + QUEEN])) == 0L
                    && (Attacks.KNIGHT[ksq] & pieces[t + KNIGHT]) == 0L
                    && (Attacks.PAWN[us][ksq] & pieces[t + PAWN] & ~(1L << capSq)) == 0L;
        }
        if (from == ksq) {
            if (flag == Move.KING_CASTLE || flag == Move.QUEEN_CASTLE) {
                if (checkers != 0L) return false;
                int step = flag == Move.KING_CASTLE ? 1 : -1;
                for (int s = from + step; s != to + step; s += step) {
                    if (isAttacked(s, them, all)) return false;
                }
                return true;
            }
            return !isAttacked(to, them, all ^ (1L << from));
        }
        if (checkers != 0L) {
            if ((checkers & (checkers - 1)) != 0L) return false;
            int csq = Long.numberOfTrailingZeros(checkers);
            if (to != csq && (Attacks.BETWEEN[ksq][csq] & (1L << to)) == 0L) return false;
        }
        if ((pinned() & (1L << from)) != 0L) {
            return (Attacks.LINE[ksq][from] & (1L << to)) != 0L;
        }
        return true;
    }

    /** Validates that an arbitrary encoded move (e.g. from the TT) is pseudo-legal here. */
    public boolean isPseudoLegal(int move) {
        if (move == Move.NONE) return false;
        int from = Move.from(move), to = Move.to(move), flag = Move.flag(move);
        int pc = board[from];
        if (pc == NO_PIECE || color(pc) != side) return false;
        int us = side;
        int target = board[to];
        if (target != NO_PIECE && (color(target) == us || type(target) == KING)) return false;
        int pt = type(pc);
        if (flag == Move.EP_CAPTURE) {
            return pt == PAWN && to == ep && (Attacks.PAWN[us][from] & (1L << to)) != 0L;
        }
        boolean capFlag = (flag & Move.CAPTURE) != 0;
        if (capFlag != (target != NO_PIECE)) return false;
        if (flag == Move.KING_CASTLE || flag == Move.QUEEN_CASTLE) {
            if (pt != KING || checkers != 0L) return false;
            if (us == WHITE) {
                if (from != 4) return false;
                if (flag == Move.KING_CASTLE) {
                    return to == 6 && (castling & WK_CASTLE) != 0 && (all & 0x60L) == 0L
                            && !isAttacked(5, BLACK, all) && !isAttacked(6, BLACK, all);
                }
                return to == 2 && (castling & WQ_CASTLE) != 0 && (all & 0x0EL) == 0L
                        && !isAttacked(3, BLACK, all) && !isAttacked(2, BLACK, all);
            }
            if (from != 60) return false;
            if (flag == Move.KING_CASTLE) {
                return to == 62 && (castling & BK_CASTLE) != 0 && (all & 0x6000000000000000L) == 0L
                        && !isAttacked(61, WHITE, all) && !isAttacked(62, WHITE, all);
            }
            return to == 58 && (castling & BQ_CASTLE) != 0 && (all & 0x0E00000000000000L) == 0L
                    && !isAttacked(59, WHITE, all) && !isAttacked(58, WHITE, all);
        }
        if (pt == PAWN) {
            boolean lastRank = us == WHITE ? to >= 56 : to < 8;
            if (lastRank != ((flag & Move.PROMO_N) != 0)) return false;
            int fwd = us == WHITE ? 8 : -8;
            if (capFlag) return (Attacks.PAWN[us][from] & (1L << to)) != 0L;
            if (flag == Move.DOUBLE_PUSH) {
                int startRank = us == WHITE ? 1 : 6;
                return (from >> 3) == startRank && to == from + 2 * fwd
                        && board[from + fwd] == NO_PIECE && target == NO_PIECE;
            }
            return to == from + fwd && target == NO_PIECE;
        }
        if ((flag & Move.PROMO_N) != 0 || flag == Move.DOUBLE_PUSH) return false;
        return (Attacks.pieceAttacks(pt, from, all) & (1L << to)) != 0L;
    }

    /** Whether the move (pseudo-legal, legal) gives check. Uses make/unmake-free detection. */
    public boolean givesCheck(int move) {
        int from = Move.from(move), to = Move.to(move), flag = Move.flag(move);
        int us = side, them = us ^ 1;
        int eksq = kingSquare(them);
        int pt = type(board[from]);
        if ((flag & Move.PROMO_N) != 0) pt = KNIGHT + (flag & 3);
        long occAfter = (all ^ (1L << from)) | (1L << to);
        if (flag == Move.EP_CAPTURE) occAfter ^= 1L << (to ^ 8);
        // direct check
        long direct = switch (pt) {
            case PAWN -> Attacks.PAWN[us][to];
            case KNIGHT -> Attacks.KNIGHT[to];
            case BISHOP -> Attacks.bishop(to, occAfter);
            case ROOK -> Attacks.rook(to, occAfter);
            case QUEEN -> Attacks.queen(to, occAfter);
            default -> 0L;
        };
        if ((direct & (1L << eksq)) != 0L) return true;
        // discovered check (also covers ep and castling-rook checks through sliders)
        int b = us * 6;
        long rq = pieces[b + ROOK] | pieces[b + QUEEN];
        long bq = pieces[b + BISHOP] | pieces[b + QUEEN];
        if (flag == Move.KING_CASTLE || flag == Move.QUEEN_CASTLE) {
            int rookTo = flag == Move.KING_CASTLE ? to - 1 : to + 1;
            int rookFrom = flag == Move.KING_CASTLE ? to + 1 : to - 2;
            long occC = (all ^ (1L << from) ^ (1L << rookFrom)) | (1L << to) | (1L << rookTo);
            return (Attacks.rook(rookTo, occC) & (1L << eksq)) != 0L;
        }
        long movedMask = ~(1L << from);
        if ((Attacks.rook(eksq, occAfter) & rq & movedMask) != 0L) return true;
        return (Attacks.bishop(eksq, occAfter) & bq & movedMask) != 0L;
    }

    // ------------------------------------------------------------------ SEE

    /** Static exchange evaluation: returns true if the move's exchange value is at least threshold. */
    public boolean seeGE(int move, int threshold) {
        int flag = Move.flag(move);
        if (flag == Move.EP_CAPTURE || flag == Move.KING_CASTLE || flag == Move.QUEEN_CASTLE
                || (flag & Move.PROMO_N) != 0) {
            return 0 >= threshold;
        }
        int from = Move.from(move), to = Move.to(move);
        int victim = board[to];
        int swap = (victim == NO_PIECE ? 0 : SEE_VALUE[type(victim)]) - threshold;
        if (swap < 0) return false;
        swap = SEE_VALUE[type(board[from])] - swap;
        if (swap <= 0) return true;
        long occupied = all ^ (1L << from) ^ (1L << to);
        int stm = side;
        long attackers = attackersTo(to, occupied);
        long bishopsQueens = pieces[BISHOP] | pieces[6 + BISHOP] | pieces[QUEEN] | pieces[6 + QUEEN];
        long rooksQueens = pieces[ROOK] | pieces[6 + ROOK] | pieces[QUEEN] | pieces[6 + QUEEN];
        int res = 1;
        while (true) {
            stm ^= 1;
            attackers &= occupied;
            long stmAttackers = attackers & occ[stm];
            if (stmAttackers == 0L) break;
            res ^= 1;
            int s = stm * 6;
            long bb;
            if ((bb = stmAttackers & pieces[s + PAWN]) != 0L) {
                if ((swap = SEE_VALUE[PAWN] - swap) < res) break;
                occupied ^= bb & -bb;
                attackers |= Attacks.bishop(to, occupied) & bishopsQueens;
            } else if ((bb = stmAttackers & pieces[s + KNIGHT]) != 0L) {
                if ((swap = SEE_VALUE[KNIGHT] - swap) < res) break;
                occupied ^= bb & -bb;
            } else if ((bb = stmAttackers & pieces[s + BISHOP]) != 0L) {
                if ((swap = SEE_VALUE[BISHOP] - swap) < res) break;
                occupied ^= bb & -bb;
                attackers |= Attacks.bishop(to, occupied) & bishopsQueens;
            } else if ((bb = stmAttackers & pieces[s + ROOK]) != 0L) {
                if ((swap = SEE_VALUE[ROOK] - swap) < res) break;
                occupied ^= bb & -bb;
                attackers |= Attacks.rook(to, occupied) & rooksQueens;
            } else if ((bb = stmAttackers & pieces[s + QUEEN]) != 0L) {
                if ((swap = SEE_VALUE[QUEEN] - swap) < res) break;
                occupied ^= bb & -bb;
                attackers |= (Attacks.bishop(to, occupied) & bishopsQueens)
                        | (Attacks.rook(to, occupied) & rooksQueens);
            } else {
                // King: can only capture if the opponent has no more attackers
                return ((attackers & ~occ[stm]) != 0L) ? (res ^ 1) != 0 : res != 0;
            }
        }
        return res != 0;
    }

    // ------------------------------------------------------------------ draws

    /** Repetition within the reversible window (any earlier occurrence counts). */
    public boolean isRepetition() {
        int end = Math.min(halfmove, pliesFromNull);
        if (end < 4) return false;
        int limit = Math.max(0, histPly - end);
        for (int i = histPly - 4; i >= limit; i -= 2) {
            if (keyHist[i] == key) return true;
        }
        return false;
    }

    /**
     * Repetition check that treats a single repeat inside the search (after {@code rootPly}) as a draw,
     * but requires a threefold repetition for positions that only repeat game history.
     */
    public boolean isRepetition(int rootHistPly) {
        int end = Math.min(halfmove, pliesFromNull);
        if (end < 4) return false;
        int limit = Math.max(0, histPly - end);
        int count = 0;
        for (int i = histPly - 4; i >= limit; i -= 2) {
            if (keyHist[i] == key) {
                if (i > rootHistPly) return true;
                if (++count >= 2) return true;
            }
        }
        return false;
    }

    public int historyPly() {
        return histPly;
    }

    public boolean isInsufficientMaterial() {
        if ((pieces[PAWN] | pieces[6 + PAWN] | pieces[ROOK] | pieces[6 + ROOK] | pieces[QUEEN] | pieces[6 + QUEEN]) != 0L) {
            return false;
        }
        int minors = Long.bitCount(pieces[KNIGHT] | pieces[6 + KNIGHT] | pieces[BISHOP] | pieces[6 + BISHOP]);
        if (minors <= 1) return true;
        // Bishops only, all on the same color complex
        if ((pieces[KNIGHT] | pieces[6 + KNIGHT]) == 0L) {
            long bishops = pieces[BISHOP] | pieces[6 + BISHOP];
            return (bishops & Attacks.LIGHT_SQUARES) == 0L || (bishops & Attacks.DARK_SQUARES) == 0L;
        }
        return false;
    }

    // ------------------------------------------------------------------ UCI helpers

    /** Parses a UCI move string against the legal moves of this position; returns NONE if illegal. */
    public int parseUciMove(String uci) {
        int[] moves = new int[256];
        int n = MoveGen.generateAll(this, moves, 0);
        for (int i = 0; i < n; i++) {
            if (Move.toUci(moves[i]).equals(uci) && isLegal(moves[i])) return moves[i];
        }
        return Move.NONE;
    }
}
