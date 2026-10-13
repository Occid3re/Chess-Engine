package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Move;
import julius.game.chessengine.core.Position;

import static julius.game.chessengine.core.Position.*;

/**
 * Per-ply accumulator stack for a search thread. {@link #push} must be called before the move is
 * made on the position (it needs the pre-move board), {@link #pushNull} before a null move.
 */
public final class Accumulators {

    private final Network net;
    private final int[][] white;
    private final int[][] black;

    public Accumulators(Network net, int maxPly) {
        this.net = net;
        white = new int[maxPly][net.hidden];
        black = new int[maxPly][net.hidden];
    }

    public void refresh(Position p, int ply) {
        net.refresh(p, white[ply], black[ply]);
    }

    public void pushNull(int ply) {
        System.arraycopy(white[ply], 0, white[ply + 1], 0, net.hidden);
        System.arraycopy(black[ply], 0, black[ply + 1], 0, net.hidden);
    }

    public void push(Position p, int m, int ply) {
        int from = Move.from(m), to = Move.to(m), flag = Move.flag(m);
        int pc = p.board[from];
        int us = color(pc);
        int subPc2 = NO_PIECE, subSq2 = -1, addPc2 = NO_PIECE, addSq2 = -1;
        int addPc1 = pc;
        if ((flag & Move.PROMO_N) != 0) addPc1 = piece(us, KNIGHT + (flag & 3));
        if (flag == Move.EP_CAPTURE) {
            subSq2 = to ^ 8;
            subPc2 = p.board[subSq2];
        } else if ((flag & Move.CAPTURE) != 0) {
            subSq2 = to;
            subPc2 = p.board[to];
        } else if (flag == Move.KING_CASTLE || flag == Move.QUEEN_CASTLE) {
            int rookFrom = flag == Move.KING_CASTLE ? to + 1 : to - 2;
            int rookTo = flag == Move.KING_CASTLE ? to - 1 : to + 1;
            subSq2 = rookFrom;
            subPc2 = p.board[rookFrom];
            addSq2 = rookTo;
            addPc2 = subPc2;
        }
        for (int persp = 0; persp < 2; persp++) {
            int[][] stack = persp == WHITE ? white : black;
            int s1 = Network.featureIndex(persp, pc, from);
            int a1 = Network.featureIndex(persp, addPc1, to);
            int s2 = subPc2 == NO_PIECE ? -1 : Network.featureIndex(persp, subPc2, subSq2);
            int a2 = addPc2 == NO_PIECE ? -1 : Network.featureIndex(persp, addPc2, addSq2);
            net.update(stack[ply], stack[ply + 1], s1, s2, a1, a2);
        }
    }

    /** Evaluation in centipawns from the side to move's perspective. */
    public int evaluate(Position p, int ply) {
        return p.side == WHITE ? net.evaluate(white[ply], black[ply]) : net.evaluate(black[ply], white[ply]);
    }
}
