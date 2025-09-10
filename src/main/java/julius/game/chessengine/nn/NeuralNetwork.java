package julius.game.chessengine.nn;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Very small feedforward network with a single linear layer. The goal of this
 * class is not to provide a strong evaluation function but to offer a simple
 * hook that can be trained via self play.  The weights are persisted to disk so
 * that training results survive application restarts.
 */
public class NeuralNetwork {

    private static final Path MODEL_PATH = Paths.get("model.dat");
    private static final int FEATURE_COUNT = 65; // 64 squares + side to move
    private static final double LEARNING_RATE = 0.001d;

    private final double[] weights = new double[FEATURE_COUNT];

    private static final NeuralNetwork INSTANCE = new NeuralNetwork();

    private NeuralNetwork() {
        load();
    }

    public static NeuralNetwork getInstance() {
        return INSTANCE;
    }

    /**
     * Evaluates the given feature vector and returns a score from the
     * perspective of white.  Positive values favour white, negative values favour
     * black.
     */
    public synchronized double evaluate(double[] features) {
        double sum = 0d;
        for (int i = 0; i < Math.min(features.length, weights.length); i++) {
            sum += features[i] * weights[i];
        }
        return sum;
    }

    /**
     * Trains the network on the supplied examples.  The result should be 1 for a
     * white win, -1 for a black win and 0 for a draw.
     */
    public synchronized void train(List<double[]> featureHistory, double result) {
        for (double[] features : featureHistory) {
            double prediction = evaluate(features);
            double error = result - prediction;
            for (int i = 0; i < Math.min(features.length, weights.length); i++) {
                weights[i] += LEARNING_RATE * error * features[i];
            }
        }
    }

    /**
     * Saves the current weights to {@link #MODEL_PATH}.
     */
    public synchronized void save() {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(MODEL_PATH))) {
            for (double w : weights) {
                out.writeDouble(w);
            }
        } catch (IOException e) {
            // best effort – training should not fail if persisting fails
        }
    }

    private void load() {
        if (!Files.exists(MODEL_PATH)) {
            return; // start with zero initialisation
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(MODEL_PATH))) {
            for (int i = 0; i < weights.length; i++) {
                weights[i] = in.readDouble();
            }
        } catch (IOException e) {
            // ignore and keep default weights
        }
    }

    /** Utility method used by tests to reset the weights. */
    public synchronized void reset() {
        for (int i = 0; i < weights.length; i++) {
            weights[i] = 0d;
        }
        save();
    }
}

