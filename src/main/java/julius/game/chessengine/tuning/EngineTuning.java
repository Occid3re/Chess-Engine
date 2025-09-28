package julius.game.chessengine.tuning;

import julius.game.chessengine.evaluation.EvaluationParameters;
import julius.game.chessengine.evaluation.EvaluationWeights;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Aggregates the tunable aspects of the engine. Instances are immutable and can be safely shared
 * across threads.
 */
public final class EngineTuning {

    private final String name;
    private final AiTuning ai;
    private final EvaluationTuning evaluation;
    private final EvaluationParameters evaluationParameters;

    private EngineTuning(Builder builder) {
        this.name = builder.name;
        this.ai = builder.ai;
        this.evaluation = builder.evaluation;
        this.evaluationParameters = builder.evaluationParameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public String name() {
        return name;
    }

    public AiTuning ai() {
        return ai;
    }

    public EvaluationTuning evaluation() {
        return evaluation;
    }

    public Map<String, Double> numericParameters() {
        return evaluationParameters.parameters();
    }

    public EvaluationParameters evaluationParameters() {
        return evaluationParameters;
    }

    public EvaluationWeights evaluationWeights() {
        return evaluation.toWeights();
    }

    public EngineTuning mutate(Random random, double strength) {
        Objects.requireNonNull(random, "random");
        if (strength <= 0.0) {
            return this;
        }
        AiTuning mutatedAi = ai; // search parameters remain fixed during tuning
        EvaluationTuning mutatedEval = evaluation.mutate(random, strength);
        Map<String, Double> mutatedNumeric = mutateNumericParameters(random, strength);
        return EngineTuning.builder()
                .name(name + "_mut")
                .ai(mutatedAi)
                .evaluation(mutatedEval)
                .numericParameters(mutatedNumeric)
                .build();
    }

    private Map<String, Double> mutateNumericParameters(Random random, double strength) {
        Map<String, Double> numericParameters = evaluationParameters.parameters();
        if (numericParameters.isEmpty()) {
            return numericParameters;
        }
        Map<String, Double> mutated = new LinkedHashMap<>();
        numericParameters.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            double variance = Math.max(0.05, strength);
            double factor = 1.0 + random.nextGaussian() * variance;
            double newValue = value * factor;
            mutated.put(key, newValue);
        });
        return Collections.unmodifiableMap(mutated);
    }

    public EngineTuning rename(String newName) {
        return toBuilder().name(newName).build();
    }

    public static final class Builder {
        private String name = "baseline";
        private AiTuning ai = AiTuning.defaults();
        private EvaluationTuning evaluation = EvaluationTuning.identity();
        private EvaluationParameters evaluationParameters = EvaluationParameters.identity();

        private Builder() {
        }

        private Builder(EngineTuning source) {
            this.name = source.name;
            this.ai = source.ai;
            this.evaluation = source.evaluation;
            this.evaluationParameters = source.evaluationParameters;
        }

        public Builder name(String name) {
            if (name != null && !name.isBlank()) {
                this.name = name;
            }
            return this;
        }

        public Builder ai(AiTuning ai) {
            this.ai = Objects.requireNonNull(ai, "ai");
            return this;
        }

        public Builder evaluation(EvaluationTuning evaluation) {
            this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
            return this;
        }

        public Builder numericParameters(Map<String, Double> parameters) {
            this.evaluationParameters = EvaluationParameters.of(parameters);
            return this;
        }

        public EngineTuning build() {
            return new EngineTuning(this);
        }
    }
}
