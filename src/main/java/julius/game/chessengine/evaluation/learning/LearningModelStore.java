package julius.game.chessengine.evaluation.learning;

import julius.game.chessengine.tuning.ParamId;
import julius.game.chessengine.tuning.ParameterRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Stores and serves a simple feed-forward neural network used by the learning evaluation module.
 * The store now resolves its weights and biases from the tuning registry so the auto-tuner can
 * mutate every coefficient without touching external checkpoint files.
 */
public final class LearningModelStore {

    private static final int INPUT_SIZE = LearningEvaluationModule.FEATURE_VECTOR_SIZE;
    private static final int[] LAYER_WIDTHS = {8, 2};
    private static final Activation[] LAYER_ACTIVATIONS = {Activation.RELU, Activation.LINEAR};

    private static final double[][][] DEFAULT_LAYER_WEIGHTS = {
            {
                    {0.05, 0.04, 0.03, 0.02, 0.01, 0.05, 0.02, 0.01, 0.01, 0.02, 0.03, 0.04, 0.03, 0.02, 0.01, 0.0, -0.01, -0.02, -0.03},
                    {-0.02, -0.01, 0.0, 0.01, 0.02, -0.03, -0.02, -0.01, 0.0, 0.01, 0.02, 0.03, 0.02, 0.01, 0.0, -0.01, -0.02, -0.03, -0.04},
                    {0.01, 0.02, 0.03, 0.04, 0.05, 0.02, 0.03, 0.04, 0.05, 0.04, 0.03, 0.02, 0.01, 0.0, -0.01, -0.02, -0.03, -0.04, -0.05},
                    {0.0, 0.0, 0.01, 0.02, 0.03, 0.0, 0.01, 0.02, 0.03, 0.02, 0.01, 0.0, -0.01, -0.02, -0.03, -0.02, -0.01, 0.0, 0.01},
                    {-0.03, -0.02, -0.01, 0.0, 0.01, -0.02, -0.01, 0.0, 0.01, 0.02, 0.03, 0.02, 0.01, 0.0, -0.01, -0.02, -0.03, -0.02, -0.01},
                    {0.02, 0.01, 0.0, -0.01, -0.02, 0.03, 0.02, 0.01, 0.0, -0.01, -0.02, -0.03, -0.02, -0.01, 0.0, 0.01, 0.02, 0.03, 0.04},
                    {0.04, 0.03, 0.02, 0.01, 0.0, 0.03, 0.02, 0.01, 0.0, -0.01, -0.02, -0.03, -0.04, -0.03, -0.02, -0.01, 0.0, 0.01, 0.02},
                    {-0.01, -0.02, -0.03, -0.04, -0.05, -0.01, -0.02, -0.03, -0.04, -0.03, -0.02, -0.01, 0.0, 0.01, 0.02, 0.03, 0.04, 0.05, 0.06}
            },
            {
                    {0.4, 0.2, 0.1, 0.0, -0.1, 0.3, 0.2, -0.2},
                    {-0.3, -0.1, 0.0, 0.1, 0.2, -0.2, -0.1, 0.3}
            }
    };

    private static final double[][] DEFAULT_LAYER_BIASES = {
            {0.02, -0.01, 0.0, 0.01, -0.02, 0.0, 0.01, -0.01},
            {0.0, 0.0}
    };

    private static final double[] DEFAULT_OUTPUT_SCALES = {120.0, 80.0};

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile LearningModel model;
    private volatile Definition definition;

    public LearningModelStore() {
        applyDefinition(buildDefinitionFromParameters());
    }

    LearningModelStore(Definition definition) {
        applyDefinition(Objects.requireNonNull(definition, "definition"));
    }

    public int inputSize() {
        LearningModel local = model;
        return local != null ? local.inputSize : 0;
    }

    public double[] infer(double[] features) {
        return inferWithScales(features).output();
    }

    public InferenceResult inferWithScales(double[] features) {
        Objects.requireNonNull(features, "features");
        LearningModel local = model;
        if (local == null) {
            throw new IllegalStateException("Learning model has not been loaded yet");
        }
        lock.readLock().lock();
        try {
            double[] output = local.infer(features);
            return new InferenceResult(output, local.outputScales());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void reloadFromParameters() {
        applyDefinition(buildDefinitionFromParameters());
    }

    public Definition currentDefinition() {
        lock.readLock().lock();
        try {
            return definition;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void applyDefinition(Definition newDefinition) {
        lock.writeLock().lock();
        try {
            this.definition = newDefinition.validate();
            this.model = LearningModel.fromDefinition(this.definition);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static Definition buildDefinitionFromParameters() {
        List<LayerDefinition> layers = new ArrayList<>();
        int previousWidth = INPUT_SIZE;
        for (int layerIndex = 0; layerIndex < LAYER_WIDTHS.length; layerIndex++) {
            int outputWidth = LAYER_WIDTHS[layerIndex];
            double[][] weights = resolveWeights(layerIndex, previousWidth, DEFAULT_LAYER_WEIGHTS[layerIndex]);
            double[] bias = resolveBias(layerIndex, DEFAULT_LAYER_BIASES[layerIndex]);
            layers.add(new LayerDefinition(weights, bias, LAYER_ACTIVATIONS[layerIndex].name()));
            previousWidth = outputWidth;
        }
        double[] outputScales = resolveOutputScales(previousWidth);
        return new Definition(INPUT_SIZE, layers, outputScales);
    }

    private static double[][] resolveWeights(int layerIndex, int inputWidth, double[][] defaults) {
        double[][] weights = new double[defaults.length][inputWidth];
        for (int neuron = 0; neuron < defaults.length; neuron++) {
            double[] neuronDefaults = defaults[neuron];
            if (neuronDefaults.length != inputWidth) {
                throw new IllegalStateException("Default weight matrix mismatch for layer " + layerIndex);
            }
            for (int input = 0; input < inputWidth; input++) {
                double fallback = neuronDefaults[input];
                double value = ParameterRegistry.get(weightKey(layerIndex, neuron, input), fallback);
                weights[neuron][input] = value;
            }
        }
        return weights;
    }

    private static double[] resolveBias(int layerIndex, double[] defaults) {
        double[] bias = new double[defaults.length];
        for (int neuron = 0; neuron < defaults.length; neuron++) {
            double fallback = defaults[neuron];
            double value = ParameterRegistry.get(biasKey(layerIndex, neuron), fallback);
            bias[neuron] = value;
        }
        return bias;
    }

    private static double[] resolveOutputScales(int outputSize) {
        double[] scales = new double[outputSize];
        for (int i = 0; i < outputSize; i++) {
            double fallback = DEFAULT_OUTPUT_SCALES.length > i ? DEFAULT_OUTPUT_SCALES[i] : 1.0;
            double value = switch (i) {
                case 0 -> ParameterRegistry.get(ParamId.LEARNING_OUTPUT_MIDGAME_SCALE);
                case 1 -> ParameterRegistry.get(ParamId.LEARNING_OUTPUT_ENDGAME_SCALE);
                default -> ParameterRegistry.get(outputScaleKey(i), fallback);
            };
            scales[i] = value;
        }
        return scales;
    }

    private static String weightKey(int layerIndex, int neuronIndex, int inputIndex) {
        return "learning.layer" + layerIndex + ".neuron" + neuronIndex + ".weight" + inputIndex;
    }

    private static String biasKey(int layerIndex, int neuronIndex) {
        return "learning.layer" + layerIndex + ".neuron" + neuronIndex + ".bias";
    }

    private static String outputScaleKey(int outputIndex) {
        return "learning.output.scale" + outputIndex;
    }

    private record LearningModel(int inputSize, List<Layer> layers, double[] outputScales) {

        static LearningModel fromDefinition(Definition definition) {
            Objects.requireNonNull(definition, "definition");
            int input = definition.inputSize;
            List<Layer> compiled = new ArrayList<>();
            int previousWidth = input;
            for (LayerDefinition layer : definition.layers) {
                compiled.add(Layer.fromDefinition(layer, previousWidth));
                previousWidth = layer.outputSize();
            }
            double[] resolvedScales = resolveScales(definition.outputScalesRaw(), previousWidth);
            return new LearningModel(input, Collections.unmodifiableList(compiled), resolvedScales);
        }

        double[] infer(double[] features) {
            if (features.length != inputSize) {
                throw new IllegalArgumentException("Expected feature vector of length " + inputSize
                        + " but received " + features.length);
            }
            double[] current = features.clone();
            for (Layer layer : layers) {
                current = layer.forward(current);
            }
            return current;
        }

        public double[] outputScales() {
            return outputScales.clone();
        }

        private static double[] resolveScales(double[] candidate, int expectedLength) {
            double[] resolved;
            if (candidate == null || candidate.length == 0) {
                resolved = new double[expectedLength];
                Arrays.fill(resolved, 1.0);
            } else {
                if (candidate.length != expectedLength) {
                    throw new IllegalArgumentException("Output scales length " + candidate.length
                            + " does not match output width " + expectedLength);
                }
                resolved = candidate.clone();
            }
            return resolved;
        }
    }

    private record Layer(double[][] weights, double[] bias, Activation activation) {

        static Layer fromDefinition(LayerDefinition definition, int expectedInput) {
            Objects.requireNonNull(definition, "definition");
            double[][] weights = definition.weights();
            if (weights == null || weights.length == 0) {
                throw new IllegalArgumentException("Layer must provide at least one neuron");
            }
            int outputSize = weights.length;
            for (double[] row : weights) {
                if (row.length != expectedInput) {
                    throw new IllegalArgumentException("Layer expected input width " + expectedInput
                            + " but encountered row with length " + row.length);
                }
            }
            double[] bias = definition.bias();
            if (bias == null || bias.length != outputSize) {
                throw new IllegalArgumentException("Layer bias length " + (bias == null ? 0 : bias.length)
                        + " does not match output size " + outputSize);
            }
            Activation activation = Activation.fromString(definition.activation());
            return new Layer(copy(weights), bias.clone(), activation);
        }

        double[] forward(double[] input) {
            int outputSize = bias.length;
            double[] output = new double[outputSize];
            for (int neuron = 0; neuron < outputSize; neuron++) {
                double sum = bias[neuron];
                double[] weightRow = weights[neuron];
                for (int i = 0; i < input.length; i++) {
                    sum += weightRow[i] * input[i];
                }
                output[neuron] = activation.apply(sum);
            }
            return output;
        }

        private static double[][] copy(double[][] source) {
            double[][] copy = new double[source.length][];
            for (int i = 0; i < source.length; i++) {
                copy[i] = source[i].clone();
            }
            return copy;
        }
    }

    private enum Activation {
        RELU {
            @Override
            double apply(double value) {
                return Math.max(0.0, value);
            }
        },
        TANH {
            @Override
            double apply(double value) {
                return Math.tanh(value);
            }
        },
        LINEAR {
            @Override
            double apply(double value) {
                return value;
            }
        };

        abstract double apply(double value);

        static Activation fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return LINEAR;
            }
            return switch (raw.toUpperCase(Locale.ROOT)) {
                case "RELU" -> RELU;
                case "TANH" -> TANH;
                default -> LINEAR;
            };
        }
    }

    public static final class Definition {
        private final int inputSize;
        private final List<LayerDefinition> layers;
        private final double[] outputScales;

        public Definition(int inputSize, List<LayerDefinition> layers) {
            this(inputSize, layers, null);
        }

        public Definition(int inputSize, List<LayerDefinition> layers, double[] outputScales) {
            this.inputSize = inputSize;
            this.layers = (layers == null ? List.of() : List.copyOf(layers));
            this.outputScales = outputScales == null ? null : outputScales.clone();
        }

        public int inputSize() {
            return inputSize;
        }

        public List<LayerDefinition> layers() {
            return layers;
        }

        double[] outputScalesRaw() {
            return outputScales == null ? null : outputScales.clone();
        }

        public double[] outputScales() {
            return outputScalesRaw();
        }

        Definition validate() {
            if (inputSize <= 0) {
                throw new IllegalArgumentException("Input size must be positive");
            }
            if (layers.isEmpty()) {
                throw new IllegalArgumentException("Model must contain at least one layer");
            }
            int previousWidth = inputSize;
            for (LayerDefinition layer : layers) {
                layer.validate(previousWidth);
                previousWidth = layer.outputSize();
            }
            if (outputScales != null && outputScales.length != previousWidth) {
                throw new IllegalArgumentException("Output scales length " + outputScales.length
                        + " must match final layer output width " + previousWidth);
            }
            return this;
        }

        public static Definition identity(int inputSize) {
            double[][] weights = new double[2][inputSize];
            double[] bias = new double[2];
            List<LayerDefinition> layers = List.of(new LayerDefinition(weights, bias, "LINEAR"));
            double[] scales = new double[] {1.0, 1.0};
            return new Definition(inputSize, layers, scales);
        }
    }

    public record InferenceResult(double[] output, double[] outputScales) {

        public InferenceResult {
            output = output.clone();
            outputScales = outputScales.clone();
        }

        public double[] output() {
            return output.clone();
        }

        public double[] outputScales() {
            return outputScales.clone();
        }
    }

    public static final class LayerDefinition {
        private final double[][] weights;
        private final double[] bias;
        private final String activation;

        public LayerDefinition(double[][] weights, double[] bias, String activation) {
            this.weights = weights;
            this.bias = bias;
            this.activation = activation;
        }

        public double[][] weights() {
            return weights;
        }

        public double[] bias() {
            return bias;
        }

        public String activation() {
            return activation;
        }

        void validate(int expectedInput) {
            if (weights == null || weights.length == 0) {
                throw new IllegalArgumentException("Layer must define weights");
            }
            for (double[] row : weights) {
                if (row.length != expectedInput) {
                    throw new IllegalArgumentException("Expected layer input width " + expectedInput
                            + " but encountered row of length " + row.length);
                }
            }
            if (bias == null || bias.length != weights.length) {
                throw new IllegalArgumentException("Layer bias must contain " + weights.length + " entries");
            }
        }

        int outputSize() {
            return weights != null ? weights.length : 0;
        }
    }
}

