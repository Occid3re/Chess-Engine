package julius.game.chessengine.evaluation;

import java.util.Objects;

/**
 * Carries the information required to update evaluation modules when a move is made.
 */
public record MoveContext(int move, EvaluationContext previousContext, EvaluationContext currentContext) {

    public MoveContext(int move, EvaluationContext previousContext, EvaluationContext currentContext) {
        this.move = move;
        this.previousContext = previousContext;
        this.currentContext = Objects.requireNonNull(currentContext, "currentContext");
    }

}
