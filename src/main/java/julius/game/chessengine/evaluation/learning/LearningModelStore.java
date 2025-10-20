package julius.game.chessengine.evaluation.learning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Stores and serves a simple feed-forward neural network used by the learning evaluation module.
 * The store is responsible for loading and saving checkpoints expressed as JSON structures under
 * {@code src/main/resources/learning/}. Inference requests are thread-safe and reuse a cached
 * in-memory representation of the model weights.
 */
public final class LearningModelStore {

    private static final Path DEFAULT_DIRECTORY = Paths.get("src", "main", "resources", "learning");
    private static final String DEFAULT_CHECKPOINT = "default-model.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path directory;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile LearningModel model;
    private volatile Definition definition;

    /**
     * Creates a store that loads the default checkpoint from {@code src/main/resources/learning}.
     */
    public LearningModelStore() {
        this(DEFAULT_DIRECTORY, DEFAULT_CHECKPOINT);
    }

    /**
     * Creates a store rooted at the provided directory and loads the given checkpoint.
     */
    public LearningModelStore(Path directory, String checkpointName) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(checkpointName, "checkpointName");
        this.directory = directory;
        ensureDirectoryExists(directory);
        loadCheckpoint(checkpointName);
    }

    /**
     * Creates a store backed by the provided model definition. Primarily used by tests.
     */
    LearningModelStore(Definition definition) {
        this.directory = DEFAULT_DIRECTORY;
        applyDefinition(Objects.requireNonNull(definition, "definition"));
    }

    /**
     * Returns the number of expected input features.
     */
    public int inputSize() {
        LearningModel local = model;
        return local != null ? local.inputSize : 0;
    }

    /**
     * Performs inference using the cached model. The returned array contains two values representing
     * the midgame and endgame centipawn contributions respectively.
     */
    public double[] infer(double[] features) {
        Objects.requireNonNull(features, "features");
        LearningModel local = model;
        if (local == null) {
            throw new IllegalStateException("Learning model has not been loaded yet");
        }
        lock.readLock().lock();
        try {
            return local.infer(features);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reloads the model from the specified checkpoint.
     */
    public void loadCheckpoint(String checkpointName) {
        Objects.requireNonNull(checkpointName, "checkpointName");
        Path checkpoint = directory.resolve(checkpointName);
        Definition parsed;
        if (Files.exists(checkpoint)) {
            parsed = readDefinition(checkpoint);
        } else {
            parsed = Definition.identity(LearningEvaluationModule.FEATURE_VECTOR_SIZE);
            writeDefinition(checkpoint, parsed);
        }
        applyDefinition(parsed);
    }

    /**
     * Persists the current model definition to the provided checkpoint file.
     */
    public void saveCheckpoint(String checkpointName) {
        Objects.requireNonNull(checkpointName, "checkpointName");
        Definition snapshot;
        lock.readLock().lock();
        try {
            snapshot = this.definition;
        } finally {
            lock.readLock().unlock();
        }
        if (snapshot == null) {
            throw new IllegalStateException("No model definition loaded");
        }
        Path checkpoint = directory.resolve(checkpointName);
        writeDefinition(checkpoint, snapshot);
    }

    /**
     * Exposes the current model definition for inspection or serialization.
     */
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

    private static void ensureDirectoryExists(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create model directory " + directory, e);
        }
    }

    private static Definition readDefinition(Path checkpoint) {
        try (InputStream in = Files.newInputStream(checkpoint)) {
            return OBJECT_MAPPER.readValue(in, Definition.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read learning model from " + checkpoint, e);
        }
    }

    private static void writeDefinition(Path checkpoint, Definition definition) {
        try (OutputStream out = Files.newOutputStream(checkpoint)) {
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, definition);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write learning model to " + checkpoint, e);
        }
    }

    private record LearningModel(int inputSize, List<Layer> layers) {

        static LearningModel fromDefinition(Definition definition) {
            Objects.requireNonNull(definition, "definition");
            int input = definition.inputSize;
            List<Layer> compiled = new ArrayList<>();
            int previousWidth = input;
            for (LayerDefinition layer : definition.layers) {
                compiled.add(Layer.fromDefinition(layer, previousWidth));
                previousWidth = layer.outputSize();
            }
            return new LearningModel(input, Collections.unmodifiableList(compiled));
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Definition {
        private final int inputSize;
        private final List<LayerDefinition> layers;

        @JsonCreator
        public Definition(@JsonProperty("inputSize") int inputSize,
                           @JsonProperty("layers") List<LayerDefinition> layers) {
            this.inputSize = inputSize;
            this.layers = (layers == null ? List.of() : List.copyOf(layers));
        }

        public int inputSize() {
            return inputSize;
        }

        public List<LayerDefinition> layers() {
            return layers;
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
            return this;
        }

        public static Definition identity(int inputSize) {
            double[][] weights = new double[2][inputSize];
            double[] bias = new double[2];
            List<LayerDefinition> layers = List.of(new LayerDefinition(weights, bias, "LINEAR"));
            return new Definition(inputSize, layers);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class LayerDefinition {
        private final double[][] weights;
        private final double[] bias;
        private final String activation;

        @JsonCreator
        public LayerDefinition(@JsonProperty("weights") double[][] weights,
                               @JsonProperty("bias") double[] bias,
                               @JsonProperty("activation") String activation) {
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
