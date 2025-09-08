package julius.game.chessengine.ai;

import lombok.Getter;

@Getter
public class TranspositionTableEntry {
    double score;
    int depth;
    NodeType nodeType;
    int bestMove; // Added to store the best move

    public TranspositionTableEntry(double score, int depth, NodeType nodeType, int bestMove) {
        this.score = score;
        this.depth = depth;
        this.nodeType = nodeType;
        this.bestMove = bestMove;
    }

    @Override
    public String toString() {
        return "TranspositionTableEntry{" +
                "score=" + score +
                ", depth=" + depth +
                ", nodeType=" + nodeType +
                ", bestMove=" + bestMove +
                '}';
    }
}

