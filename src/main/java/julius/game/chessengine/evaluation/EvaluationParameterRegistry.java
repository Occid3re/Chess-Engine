package julius.game.chessengine.evaluation;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ensures that evaluation modules register their tunable parameters before tuning defaults are
 * requested. This avoids builder code observing an empty parameter set if the modules have not been
 * loaded yet.
 */
public final class EvaluationParameterRegistry {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private EvaluationParameterRegistry() {
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        initialize(MaterialModule.class);
        initialize(PawnStructureModule.class);
        initialize(ActivityModule.class);
        initialize(KingSafetyModule.class);
        initialize(SpaceControlModule.class);
        initialize(ThreatModule.class);
        initialize(EvaluationPipeline.class);
    }

    private static void initialize(Class<?> type) {
        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to initialize evaluation parameters for " + type, e);
        }
    }
}
