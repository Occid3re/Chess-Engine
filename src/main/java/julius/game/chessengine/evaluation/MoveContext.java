package julius.game.chessengine.evaluation;

import java.util.Objects;

/**
 * Carries the information required to update evaluation modules when a move is made.
 */
public final class MoveContext {

    private final int move;
    private final EvaluationContext previousContext;
    private final EvaluationContext currentContext;

    public MoveContext(int move, EvaluationContext previousContext, EvaluationContext currentContext) {
        this.move = move;
        this.previousContext = previousContext;
        this.currentContext = Objects.requireNonNull(currentContext, "currentContext");
    }

    public int getMove() {
        return move;
    }

    public EvaluationContext getPreviousContext() {
        return previousContext;
    }

    public EvaluationContext getCurrentContext() {
        return currentContext;
    }
}
