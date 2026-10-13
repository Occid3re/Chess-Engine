package julius.game.chessengine.core;

import static julius.game.chessengine.core.Position.*;

/**
 * Pseudo-legal move generation into caller-provided int buffers.
 * "Noisy" moves are captures and all promotions; "quiet" moves are everything else including castling.
 */
public final class MoveGen {

    private MoveGen() {
    }

    public static int generateAll(Position p, int[] moves, int n) {
        n = generateNoisy(p, moves, n);
        return generateQuiets(p, moves, n);
    }

    public static int generateNoisy(Position p, int[] moves, int n) {
        int us = p.side, them = us ^ 1;
        long enemies = p.occ[them];
        long empty = ~p.all;
        long pawns = p.pieces[us * 6 + PAWN];

        if (us == WHITE) {
            long promoPush = (pawns << 8) & empty & Attacks.RANK_8;
            while (promoPush != 0L) {
                int to = Long.numberOfTrailingZeros(promoPush);
                promoPush &= promoPush - 1;
                n = addPromotions(moves, n, to - 8, to, false);
            }
            long capL = ((pawns & ~Attacks.FILE_A) << 7) & enemies;
            long capR = ((pawns & ~Attacks.FILE_H) << 9) & enemies;
            while (capL != 0L) {
                int to = Long.numberOfTrailingZeros(capL);
                capL &= capL - 1;
                if (to >= 56) n = addPromotions(moves, n, to - 7, to, true);
                else moves[n++] = Move.make(to - 7, to, Move.CAPTURE);
            }
            while (capR != 0L) {
                int to = Long.numberOfTrailingZeros(capR);
                capR &= capR - 1;
                if (to >= 56) n = addPromotions(moves, n, to - 9, to, true);
                else moves[n++] = Move.make(to - 9, to, Move.CAPTURE);
            }
        } else {
            long promoPush = (pawns >>> 8) & empty & Attacks.RANK_1;
            while (promoPush != 0L) {
                int to = Long.numberOfTrailingZeros(promoPush);
                promoPush &= promoPush - 1;
                n = addPromotions(moves, n, to + 8, to, false);
            }
            long capL = ((pawns & ~Attacks.FILE_A) >>> 9) & enemies;
            long capR = ((pawns & ~Attacks.FILE_H) >>> 7) & enemies;
            while (capL != 0L) {
                int to = Long.numberOfTrailingZeros(capL);
                capL &= capL - 1;
                if (to < 8) n = addPromotions(moves, n, to + 9, to, true);
                else moves[n++] = Move.make(to + 9, to, Move.CAPTURE);
            }
            while (capR != 0L) {
                int to = Long.numberOfTrailingZeros(capR);
                capR &= capR - 1;
                if (to < 8) n = addPromotions(moves, n, to + 7, to, true);
                else moves[n++] = Move.make(to + 7, to, Move.CAPTURE);
            }
        }
        if (p.ep >= 0) {
            long from = Attacks.PAWN[them][p.ep] & pawns;
            while (from != 0L) {
                int f = Long.numberOfTrailingZeros(from);
                from &= from - 1;
                moves[n++] = Move.make(f, p.ep, Move.EP_CAPTURE);
            }
        }
        return generatePieces(p, moves, n, enemies, Move.CAPTURE);
    }

    public static int generateQuiets(Position p, int[] moves, int n) {
        int us = p.side;
        long empty = ~p.all;
        long pawns = p.pieces[us * 6 + PAWN];
        if (us == WHITE) {
            long push1 = (pawns << 8) & empty & ~Attacks.RANK_8;
            long push2 = ((push1 & Attacks.RANK_3) << 8) & empty;
            while (push1 != 0L) {
                int to = Long.numberOfTrailingZeros(push1);
                push1 &= push1 - 1;
                moves[n++] = Move.make(to - 8, to, Move.QUIET);
            }
            while (push2 != 0L) {
                int to = Long.numberOfTrailingZeros(push2);
                push2 &= push2 - 1;
                moves[n++] = Move.make(to - 16, to, Move.DOUBLE_PUSH);
            }
            if ((p.castling & (WK_CASTLE | WQ_CASTLE)) != 0 && p.checkers == 0L) {
                if ((p.castling & WK_CASTLE) != 0 && (p.all & 0x60L) == 0L
                        && !p.isAttacked(5, BLACK, p.all) && !p.isAttacked(6, BLACK, p.all)) {
                    moves[n++] = Move.make(4, 6, Move.KING_CASTLE);
                }
                if ((p.castling & WQ_CASTLE) != 0 && (p.all & 0x0EL) == 0L
                        && !p.isAttacked(3, BLACK, p.all) && !p.isAttacked(2, BLACK, p.all)) {
                    moves[n++] = Move.make(4, 2, Move.QUEEN_CASTLE);
                }
            }
        } else {
            long push1 = (pawns >>> 8) & empty & ~Attacks.RANK_1;
            long push2 = ((push1 & Attacks.RANK_6) >>> 8) & empty;
            while (push1 != 0L) {
                int to = Long.numberOfTrailingZeros(push1);
                push1 &= push1 - 1;
                moves[n++] = Move.make(to + 8, to, Move.QUIET);
            }
            while (push2 != 0L) {
                int to = Long.numberOfTrailingZeros(push2);
                push2 &= push2 - 1;
                moves[n++] = Move.make(to + 16, to, Move.DOUBLE_PUSH);
            }
            if ((p.castling & (BK_CASTLE | BQ_CASTLE)) != 0 && p.checkers == 0L) {
                if ((p.castling & BK_CASTLE) != 0 && (p.all & 0x6000000000000000L) == 0L
                        && !p.isAttacked(61, WHITE, p.all) && !p.isAttacked(62, WHITE, p.all)) {
                    moves[n++] = Move.make(60, 62, Move.KING_CASTLE);
                }
                if ((p.castling & BQ_CASTLE) != 0 && (p.all & 0x0E00000000000000L) == 0L
                        && !p.isAttacked(59, WHITE, p.all) && !p.isAttacked(58, WHITE, p.all)) {
                    moves[n++] = Move.make(60, 58, Move.QUEEN_CASTLE);
                }
            }
        }
        return generatePieces(p, moves, n, empty, Move.QUIET);
    }

    private static int generatePieces(Position p, int[] moves, int n, long targets, int flag) {
        int b = p.side * 6;
        long all = p.all;
        long bb = p.pieces[b + KNIGHT];
        while (bb != 0L) {
            int from = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            n = addAll(moves, n, from, Attacks.KNIGHT[from] & targets, flag);
        }
        bb = p.pieces[b + BISHOP];
        while (bb != 0L) {
            int from = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            n = addAll(moves, n, from, Attacks.bishop(from, all) & targets, flag);
        }
        bb = p.pieces[b + ROOK];
        while (bb != 0L) {
            int from = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            n = addAll(moves, n, from, Attacks.rook(from, all) & targets, flag);
        }
        bb = p.pieces[b + QUEEN];
        while (bb != 0L) {
            int from = Long.numberOfTrailingZeros(bb);
            bb &= bb - 1;
            n = addAll(moves, n, from, Attacks.queen(from, all) & targets, flag);
        }
        int k = Long.numberOfTrailingZeros(p.pieces[b + KING]);
        return addAll(moves, n, k, Attacks.KING[k] & targets, flag);
    }

    private static int addAll(int[] moves, int n, int from, long targets, int flag) {
        int base = from | (flag << 12);
        while (targets != 0L) {
            int to = Long.numberOfTrailingZeros(targets);
            targets &= targets - 1;
            moves[n++] = base | (to << 6);
        }
        return n;
    }

    private static int addPromotions(int[] moves, int n, int from, int to, boolean capture) {
        int base = capture ? Move.PROMO_CAPTURE_N : Move.PROMO_N;
        moves[n++] = Move.make(from, to, base + 3); // queen first
        moves[n++] = Move.make(from, to, base);
        moves[n++] = Move.make(from, to, base + 2);
        moves[n++] = Move.make(from, to, base + 1);
        return n;
    }

    // ------------------------------------------------------------------ perft

    public static long perft(Position p, int depth) {
        int[] moves = new int[256];
        int n = generateAll(p, moves, 0);
        long nodes = 0;
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (!p.isLegal(m)) continue;
            if (depth == 1) {
                nodes++;
                continue;
            }
            p.makeMove(m);
            nodes += perft(p, depth - 1);
            p.unmakeMove(m);
        }
        return nodes;
    }
}
