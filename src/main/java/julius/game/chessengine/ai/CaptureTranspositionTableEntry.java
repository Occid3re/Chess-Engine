package julius.game.chessengine.ai;

public class CaptureTranspositionTableEntry {
    double score;
    boolean isWhite;

    public CaptureTranspositionTableEntry(double score, boolean isWhite) {
        this.score = score;
        this.isWhite = isWhite;
    }

    public double getScore() {
        return score;
    }

    public boolean isWhite() {
        return isWhite;
    }

    @Override
    public String toString() {
        return "TranspositionTableEntry{" +
                "score=" + score +
                ", isWhite=" + isWhite +
                '}';
    }
}

