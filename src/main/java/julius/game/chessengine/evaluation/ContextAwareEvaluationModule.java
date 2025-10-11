package julius.game.chessengine.evaluation;

import java.util.EnumSet;

/**
 * Optional extension point for {@link EvaluationModule}s that want the evaluation pipeline to
 * invalidate them selectively when only certain {@link EvaluationContext} features change.
 */
public interface ContextAwareEvaluationModule {

    /**
     * Returns the evaluation context aspects that influence this module's cached state.
     */
    EnumSet<EvaluationContextAspect> getContextAspects();
}
