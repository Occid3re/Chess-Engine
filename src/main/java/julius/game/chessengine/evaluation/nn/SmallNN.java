package julius.game.chessengine.evaluation.nn;

import lombok.extern.log4j.Log4j2;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tiny feed-forward network for hybrid chess evaluation.
 *
 * <p>Architecture: input → Dense(H1) → ReLU → Dense(H2) → ReLU → Dense(1)
 * <p>Input is normalized (mean/std) before the first layer.
 *
 * <p>Binary weight file format (little-endian floats):
 * <pre>
 *   int32 inputSize        (= {@link FeatureExtractor#FEATURE_COUNT})
 *   int32 hidden1Size
 *   int32 hidden2Size
 *   float[inputSize] means
 *   float[inputSize] stds
 *   float[hidden1 * inputSize] W1   (row-major)
 *   float[hidden1] b1
 *   float[hidden2 * hidden1] W2     (row-major)
 *   float[hidden2] b2
 *   float[1 * hidden2] W3           (row-major)
 *   float[1] b3
 *   float outputScale               (centipawns per unit — e.g. 400 for tanh(eval/400) training)
 * </pre>
 *
 * <p>The output of the forward pass is a scalar in approximately [-1, 1] which is then
 * multiplied by {@code outputScale} to produce centipawns.
 *
 * <p>Forward pass is pure-Java, no Vector API, no allocation (uses thread-local buffers).
 * With the default architecture (70 → 64 → 32 → 1) the forward pass is ~6600 FMAs —
 * small enough to run &gt; 500K times per second in plain Java.
 */
@Log4j2
public final class SmallNN {

    private final int inputSize;
    private final int hidden1Size;
    private final int hidden2Size;
    private final float[] means;
    private final float[] stds;
    private final float[] w1;
    private final float[] b1;
    private final float[] w2;
    private final float[] b2;
    private final float[] w3;
    private final float b3;
    private final float outputScale;

    private final ThreadLocal<float[]> normalizedBuffer;
    private final ThreadLocal<float[]> hidden1Buffer;
    private final ThreadLocal<float[]> hidden2Buffer;

    private SmallNN(int inputSize, int hidden1Size, int hidden2Size,
                    float[] means, float[] stds,
                    float[] w1, float[] b1,
                    float[] w2, float[] b2,
                    float[] w3, float b3,
                    float outputScale) {
        this.inputSize = inputSize;
        this.hidden1Size = hidden1Size;
        this.hidden2Size = hidden2Size;
        this.means = means;
        this.stds = stds;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
        this.w3 = w3;
        this.b3 = b3;
        this.outputScale = outputScale;
        this.normalizedBuffer = ThreadLocal.withInitial(() -> new float[inputSize]);
        this.hidden1Buffer = ThreadLocal.withInitial(() -> new float[hidden1Size]);
        this.hidden2Buffer = ThreadLocal.withInitial(() -> new float[hidden2Size]);
    }

    /**
     * Load a weight file from a classpath resource.
     * Returns {@code null} if the resource is missing or malformed — callers should
     * fall back to classic evaluation.
     */
    public static SmallNN loadFromResource(String resourcePath) {
        try (InputStream in = SmallNN.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("NN weights resource not found: {}", resourcePath);
                return null;
            }
            return load(in);
        } catch (IOException e) {
            log.warn("Failed to load NN weights from {}: {}", resourcePath, e.toString());
            return null;
        }
    }

    public static SmallNN load(InputStream rawInput) throws IOException {
        try (DataInputStream in = new DataInputStream(rawInput)) {
            int inputSize = Integer.reverseBytes(in.readInt());
            int hidden1 = Integer.reverseBytes(in.readInt());
            int hidden2 = Integer.reverseBytes(in.readInt());

            if (inputSize != FeatureExtractor.FEATURE_COUNT) {
                throw new IOException("Input size mismatch: file has " + inputSize
                        + " but FeatureExtractor has " + FeatureExtractor.FEATURE_COUNT);
            }

            float[] means = readFloats(in, inputSize);
            float[] stds = readFloats(in, inputSize);
            float[] w1 = readFloats(in, hidden1 * inputSize);
            float[] b1 = readFloats(in, hidden1);
            float[] w2 = readFloats(in, hidden2 * hidden1);
            float[] b2 = readFloats(in, hidden2);
            float[] w3 = readFloats(in, 1 * hidden2);
            float b3 = readFloat(in);
            float outputScale = readFloat(in);

            log.info("Loaded NN: {} → {} → {} → 1, outputScale={}",
                    inputSize, hidden1, hidden2, outputScale);

            return new SmallNN(inputSize, hidden1, hidden2, means, stds,
                    w1, b1, w2, b2, w3, b3, outputScale);
        }
    }

    private static float[] readFloats(DataInputStream in, int count) throws IOException {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = readFloat(in);
        }
        return out;
    }

    private static float readFloat(DataInputStream in) throws IOException {
        int raw = in.readInt();
        // Little-endian: reverse bytes
        int reversed = Integer.reverseBytes(raw);
        return Float.intBitsToFloat(reversed);
    }

    /**
     * Run the forward pass. Returns a centipawn score from the white-to-move perspective
     * (positive = good for side to move is handled by the features themselves, which
     * include the side-to-move flag).
     *
     * <p>This method does no allocation in the hot path — all buffers are thread-local.
     *
     * @param features input vector of length {@link FeatureExtractor#FEATURE_COUNT}
     * @return centipawn evaluation
     */
    public float forward(float[] features) {
        float[] normalized = normalizedBuffer.get();
        float[] h1 = hidden1Buffer.get();
        float[] h2 = hidden2Buffer.get();

        // Normalize: (x - mean) / std
        for (int i = 0; i < inputSize; i++) {
            float s = stds[i];
            normalized[i] = (features[i] - means[i]) / (s == 0f ? 1f : s);
        }

        // Layer 1: h1 = relu(W1 * normalized + b1)
        for (int j = 0; j < hidden1Size; j++) {
            float sum = b1[j];
            int base = j * inputSize;
            for (int i = 0; i < inputSize; i++) {
                sum += w1[base + i] * normalized[i];
            }
            h1[j] = sum > 0f ? sum : 0f;
        }

        // Layer 2: h2 = relu(W2 * h1 + b2)
        for (int j = 0; j < hidden2Size; j++) {
            float sum = b2[j];
            int base = j * hidden1Size;
            for (int i = 0; i < hidden1Size; i++) {
                sum += w2[base + i] * h1[i];
            }
            h2[j] = sum > 0f ? sum : 0f;
        }

        // Layer 3: output = W3 * h2 + b3  (no activation)
        float out = b3;
        for (int i = 0; i < hidden2Size; i++) {
            out += w3[i] * h2[i];
        }

        // Scale to centipawns.
        return out * outputScale;
    }

    public int getInputSize() {
        return inputSize;
    }
}
