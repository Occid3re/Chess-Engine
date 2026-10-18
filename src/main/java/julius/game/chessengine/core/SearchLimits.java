package julius.game.chessengine.core;

/** Parameters of a "go" command. Times are in milliseconds; 0 / -1 mean "not set". */
public final class SearchLimits {
    public long wtime = -1, btime = -1, winc, binc;
    public int movestogo;
    public int depth;
    public long nodes;
    public long movetime;
    public boolean infinite;
    public boolean ponder;
    /** "go searchmoves": only these root moves are searched (null = all). */
    public int[] searchMoves;
    /** Root moves that must be better than the others by penaltyCp[i] centipawns to be chosen (null = none). */
    public int[] penaltyMoves;
    public int[] penaltyCp;

    public boolean hasClock() {
        return wtime >= 0 || btime >= 0;
    }

    public static SearchLimits depth(int d) {
        SearchLimits l = new SearchLimits();
        l.depth = d;
        return l;
    }

    public static SearchLimits movetime(long ms) {
        SearchLimits l = new SearchLimits();
        l.movetime = ms;
        return l;
    }
}
