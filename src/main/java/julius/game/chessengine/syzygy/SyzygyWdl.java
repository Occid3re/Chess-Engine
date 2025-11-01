package julius.game.chessengine.syzygy;

/**
 * Normalised win/draw/loss outcomes used by Syzygy tablebases.
 *
 * <p>The original Syzygy files expose five distinct states: wins/losses, plus the
 * so-called cursed/blesed positions where the 50-move rule may flip the result.
 * The engine keeps the richer signal so callers can apply their own heuristics.</p>
 */
public enum SyzygyWdl {
    LOSS(-2),
    BLESSED_LOSS(-1),
    DRAW(0),
    CURSED_WIN(1),
    WIN(2),
    UNKNOWN(Integer.MIN_VALUE);

    private final int score;

    SyzygyWdl(int score) {
        this.score = score;
    }

    public int score() {
        return score;
    }

    static SyzygyWdl fromName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        return switch (name) {
            case "LOSS" -> LOSS;
            case "BLESSED_LOSS" -> BLESSED_LOSS;
            case "DRAW" -> DRAW;
            case "CURSED_WIN" -> CURSED_WIN;
            case "WIN" -> WIN;
            default -> UNKNOWN;
        };
    }
}
