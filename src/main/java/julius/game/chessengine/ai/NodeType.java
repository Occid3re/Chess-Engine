package julius.game.chessengine.ai;

public enum NodeType {
    EXACT, // exact score
    LOWERBOUND, // failed high, value is a lower bound
    UPPERBOUND // failed low, value is an upper bound
}