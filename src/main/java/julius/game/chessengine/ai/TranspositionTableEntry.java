package julius.game.chessengine.ai;

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

    public double getScore() {
        return score;
    }

    public int getDepth() {
        return depth;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public int getBestMove() {
        return bestMove;
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

