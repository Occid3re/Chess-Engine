package julius.game.chessengine.evaluation;

/**
 * A pluggable evaluation component.  Modules may maintain incremental state and only recompute
 * their contribution when marked dirty by the pipeline or by themselves.
 */
public interface EvaluationModule {

    /**
     * Called once when the pipeline is initialized or when a module needs to rebuild its state from
     * scratch.
     */
    void initialize(EvaluationContext context);

    /**
     * Recompute the module's internal caches if {@link #isDirty()} returns {@code true}.
     */
    void evaluate(EvaluationContext context);

    /**
     * Apply an incremental move update.  Modules that cannot handle incremental updates may simply
     * mark themselves dirty and defer recomputation to {@link #evaluate(EvaluationContext)}.
     */
    void applyMove(MoveContext moveContext);

    /**
     * Undo a move.  The default implementation is to delegate to {@link #applyMove(MoveContext)},
     * but modules may override when they have directional state.
     */
    void undoMove(MoveContext moveContext);

    int getMidgameScore();

    int getEndgameScore();

    /**
     * Indicates whether the cached scores need to be recomputed.
     */
    boolean isDirty();

    /**
     * Mark the module as dirty so its contribution is refreshed on the next evaluation cycle.
     */
    void markDirty();
}
