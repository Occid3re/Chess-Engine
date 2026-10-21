package julius.game.chessengine.core;

/**
 * 16-bit move encoding: bits 0-5 from, bits 6-11 to, bits 12-15 flag.
 * Flag layout follows the common scheme where bit 2 marks captures and bit 3 marks promotions.
 */
public final class Move {

    public static final int NONE = 0;

    public static final int QUIET = 0;
    public static final int DOUBLE_PUSH = 1;
    public static final int KING_CASTLE = 2;
    public static final int QUEEN_CASTLE = 3;
    public static final int CAPTURE = 4;
    public static final int EP_CAPTURE = 5;
    public static final int PROMO_N = 8;
    public static final int PROMO_B = 9;
    public static final int PROMO_R = 10;
    public static final int PROMO_Q = 11;
    public static final int PROMO_CAPTURE_N = 12;
    public static final int PROMO_CAPTURE_B = 13;
    public static final int PROMO_CAPTURE_R = 14;
    public static final int PROMO_CAPTURE_Q = 15;

    private Move() {
    }

    public static int make(int from, int to, int flag) {
        return from | (to << 6) | (flag << 12);
    }

    public static int from(int move) {
        return move & 63;
    }

    public static int to(int move) {
        return (move >>> 6) & 63;
    }

    public static int flag(int move) {
        return (move >>> 12) & 15;
    }

    public static boolean isCapture(int move) {
        return (move & (CAPTURE << 12)) != 0;
    }

    public static boolean isPromotion(int move) {
        return (move & (PROMO_N << 12)) != 0;
    }

    /** Captures and promotions. */
    public static boolean isNoisy(int move) {
        return (move & ((CAPTURE | PROMO_N) << 12)) != 0;
    }

    public static boolean isQuiet(int move) {
        return !isNoisy(move);
    }

    public static boolean isCastle(int move) {
        int f = flag(move);
        return f == KING_CASTLE || f == QUEEN_CASTLE;
    }

    /** Promotion piece type (KNIGHT..QUEEN) or -1. */
    public static int promotionType(int move) {
        return isPromotion(move) ? Position.KNIGHT + (flag(move) & 3) : -1;
    }

    /** UCI notation; castling as the king's move to the g/c file (standard chess). */
    public static String toUci(int move) {
        return toUci(move, false);
    }

    /** UCI notation; castling as king takes own rook with chess960Notation (UCI_Chess960), else king to g/c file. */
    public static String toUci(int move, boolean chess960Notation) {
        if (move == NONE) return "0000";
        StringBuilder sb = new StringBuilder(5);
        int to = isCastle(move) && !chess960Notation ? Position.castleKingTo(move) : to(move);
        sb.append(squareName(from(move))).append(squareName(to));
        if (isPromotion(move)) sb.append("nbrq".charAt(flag(move) & 3));
        return sb.toString();
    }

    public static String squareName(int sq) {
        return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >> 3));
    }

    public static int parseSquare(String s) {
        return (s.charAt(0) - 'a') + 8 * (s.charAt(1) - '1');
    }
}
