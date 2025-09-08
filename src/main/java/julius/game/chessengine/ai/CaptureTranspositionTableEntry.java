package julius.game.chessengine.ai;

import lombok.Getter;

@Getter
public class CaptureTranspositionTableEntry {
    double score;
    boolean isWhite;

    public CaptureTranspositionTableEntry(double score, boolean isWhite) {
        this.score = score;
        this.isWhite = isWhite;
    }

    @Override
    public String toString() {
        return "TranspositionTableEntry{" +
                "score=" + score +
                ", isWhite=" + isWhite +
                '}';
    }
}

