package julius.game.chessengine.evaluation.nn;

import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.KingSafetyModule;
import julius.game.chessengine.evaluation.MaterialModule;
import julius.game.chessengine.evaluation.MoveContext;
import julius.game.chessengine.evaluation.PawnStructureModule;
import julius.game.chessengine.evaluation.ThreatModule;

import java.util.List;

/**
 * Blends the classic hand-crafted evaluation with a small neural network correction.
 *
 * <p>The classic modules run first and produce a tapered midgame/endgame score. That
 * score is then fed as feature 70 into the neural network, which outputs a CORRECTION
 * in centipawns. The final score is {@code classic + nn_correction}.
 *
 * <p>This approach is strictly safer than pure-NN replacement: at worst the network
 * learns to output 0, matching classic eval. At best it learns to correct systematic
 * biases that the hand-crafted features can't capture.
 *
 * <p>The module owns the five classic sub-modules internally and manages their
 * lifecycle. It is the ONLY module registered in the pipeline when neural mode is
 * active — so the pipeline's own weights collapse to identity.
 */
public final class NeuralBlendModule implements EvaluationModule {

    private final SmallNN nn;
    private final MaterialModule material;
    private final PawnStructureModule pawnStructure;
    private final ActivityModule activity;
    private final KingSafetyModule kingSafety;
    private final ThreatModule threat;
    private final List<EvaluationModule> subModules;

    private final ThreadLocal<float[]> featureBuffer;

    private EvaluationContext context;
    private int cachedMidgame;
    private int cachedEndgame;
    private boolean dirty = true;

    public NeuralBlendModule(SmallNN nn) {
        if (nn == null) {
            throw new IllegalArgumentException("SmallNN must not be null");
        }
        this.nn = nn;
        this.material = new MaterialModule();
        this.pawnStructure = new PawnStructureModule();
        this.activity = new ActivityModule();
        this.kingSafety = new KingSafetyModule();
        this.threat = new ThreatModule();
        this.material.setPawnChangeListener(pawnStructure);
        this.subModules = List.of(material, pawnStructure, activity, kingSafety, threat);
        this.featureBuffer = ThreadLocal.withInitial(() -> new float[FeatureExtractor.FEATURE_COUNT]);
    }

    @Override
    public void initialize(EvaluationContext context) {
        this.context = context;
        for (EvaluationModule m : subModules) {
            m.initialize(context);
        }
        this.dirty = true;
        evaluate(context);
    }

    @Override
    public void evaluate(EvaluationContext context) {
        this.context = context;
        if (!dirty) {
            return;
        }

        // Compute classic score from the five sub-modules.
        int classicMidgame = 0;
        int classicEndgame = 0;
        for (EvaluationModule m : subModules) {
            if (m.isDirty()) {
                m.evaluate(context);
            }
            classicMidgame += m.getMidgameScore();
            classicEndgame += m.getEndgameScore();
        }

        // Tapered blend to give the NN a single scalar prior.
        int phase = Math.max(0, Math.min(256, context.getPhase()));
        int classicBlended = (classicMidgame * (256 - phase) + classicEndgame * phase) / 256;

        // Extract features with the classic score as feature 70.
        float[] features = featureBuffer.get();
        FeatureExtractor.extract(context, features, classicBlended);

        // NN output is the correction (centipawns). Scale down to 0.2 by default —
        // the residual model has high RMSE (~100cp) so a small influence gives the
        // best tactical accuracy. Tuned empirically via BestMoveSearchTest:
        // scale 0.0 → 77/97, 0.2 → 82/97 (best), 0.3 → 81/97, 1.0 → 60/97.
        float correction = nn.forward(features);
        float scale = Float.parseFloat(System.getProperty("chessengine.nn.correctionScale", "0.2"));
        int corrected = classicBlended + Math.round(correction * scale);

        // Return the corrected value as BOTH midgame and endgame so the pipeline's
        // tapered blend (mid*(256-phase)+end*phase)/256 returns the same value in any phase.
        // We've already done the taper ourselves with classicBlended.
        cachedMidgame = corrected;
        cachedEndgame = corrected;
        dirty = false;
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        for (EvaluationModule m : subModules) {
            m.applyMove(moveContext);
        }
        dirty = true;
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        for (EvaluationModule m : subModules) {
            m.undoMove(moveContext);
        }
        dirty = true;
    }

    @Override
    public int getMidgameScore() {
        return cachedMidgame;
    }

    @Override
    public int getEndgameScore() {
        return cachedEndgame;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        dirty = true;
        for (EvaluationModule m : subModules) {
            m.markDirty();
        }
    }
}
