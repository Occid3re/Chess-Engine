package julius.game.chessengine.evaluation;

/**
 * Identifiers for coarse-grained {@link EvaluationContext} features that evaluation modules rely
 * on. The evaluation pipeline uses these aspects to invalidate only the modules whose inputs
 * changed after a context refresh.
 */
public enum EvaluationContextAspect {

    /**
     * Structural board changes such as piece placement, side to move, castling rights, or the
     * fifty-move metadata.
     */
    STRUCTURE,

    /**
     * Derived attack bitboards for each side.
     */
    ATTACK_MAPS
}
