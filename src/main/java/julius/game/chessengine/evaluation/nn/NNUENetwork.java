package julius.game.chessengine.evaluation.nn;

import lombok.extern.log4j.Log4j2;

import java.io.*;
import java.util.zip.GZIPInputStream;

/**
 * NNUE network: loads quantized weights and runs the L2+L3 forward pass.
 *
 * <p>Architecture: HalfKP(40960) -> L1(256) [incremental] -> ClippedReLU ->
 * L2(32) -> ClippedReLU -> L3(1)
 *
 * <p>L1 is managed by {@link NNUEAccumulator} (incremental updates).
 * This class handles L1 weight storage (for accumulator add/sub) and
 * the full L2+L3 forward pass.
 *
 * <p>Weight file format (little-endian):
 * <pre>
 *   int32  version          (1)
 *   int32  featureSize      (40960)
 *   int32  l1Size           (256)
 *   int32  l2Size           (32)
 *   int16[featureSize * l1Size]  L1 weights (column-major: weight[feature][neuron])
 *   int16[l1Size]                L1 biases
 *   int16[l1Size * 2 * l2Size]  L2 weights (input = 512 = 2*l1Size)
 *   int16[l2Size]                L2 biases
 *   int16[l2Size]                L3 weights (single output)
 *   int16                        L3 bias
 *   int16                        output scale (divide final result by this)
 * </pre>
 *
 * <p>All weights are int16 for quantized inference. Accumulators are int16,
 * intermediate L2 products use int32 to avoid overflow.
 */
@Log4j2
public final class NNUENetwork {

    private static final int EXPECTED_VERSION = 1;

    private final int featureSize;
    private final int l1Size;
    private final int l2Size;

    // L1 weights: [featureSize][l1Size] stored as flat array in row-major
    // Each row is one feature's weight vector (for accumulator add/sub)
    private final short[] l1Weights;
    private final short[] l1Biases;

    // L2: [l2InputSize][l2Size] where l2InputSize = 2 * l1Size = 512
    private final short[] l2Weights;
    private final short[] l2Biases;

    // L3: [l2Size] weights + bias (single output neuron)
    private final short[] l3Weights;
    private final short l3Bias;
    private final int outputScale;

    // Thread-local buffer for L2 output (avoids allocation in forward pass)
    private final ThreadLocal<int[]> l2Buffer;

    private NNUENetwork(int featureSize, int l1Size, int l2Size,
                        short[] l1Weights, short[] l1Biases,
                        short[] l2Weights, short[] l2Biases,
                        short[] l3Weights, short l3Bias, int outputScale) {
        this.featureSize = featureSize;
        this.l1Size = l1Size;
        this.l2Size = l2Size;
        this.l1Weights = l1Weights;
        this.l1Biases = l1Biases;
        this.l2Weights = l2Weights;
        this.l2Biases = l2Biases;
        this.l3Weights = l3Weights;
        this.l3Bias = l3Bias;
        this.outputScale = outputScale;
        this.l2Buffer = ThreadLocal.withInitial(() -> new int[l2Size]);
    }

    /**
     * Get the flat L1 weight array (for accumulator add/sub by offset).
     * The row for feature index {@code i} starts at offset {@code i * l1Size}.
     */
    public short[] getL1Weights() {
        return l1Weights;
    }

    /**
     * Get the offset into the L1 weight array for a given feature index.
     */
    public int getL1Offset(int featureIndex) {
        return featureIndex * l1Size;
    }

    /**
     * Get L1 biases (for accumulator initialization).
     */
    public short[] getL1Biases() {
        return l1Biases;
    }

    /**
     * Run L2+L3 forward pass on the clipped accumulator output.
     *
     * @param input clipped accumulator output, length = 2 * l1Size (512)
     * @return centipawn evaluation from the side-to-move perspective
     */
    public int forward(short[] input) {
        int l2InputSize = l1Size * 2;

        // L2: int32 accumulation to avoid overflow (zero-allocation via thread-local)
        int[] l2Out = l2Buffer.get();
        for (int j = 0; j < l2Size; j++) {
            int sum = l2Biases[j];
            int base = j * l2InputSize;
            for (int i = 0; i < l2InputSize; i++) {
                sum += (int) input[i] * (int) l2Weights[base + i];
            }
            // Quantization: divide by 64 to bring back to int16 range, then clip
            sum = sum >> 6; // equivalent to / 64
            l2Out[j] = Math.max(0, Math.min(127, sum));
        }

        // L3: single output neuron
        int output = l3Bias;
        for (int i = 0; i < l2Size; i++) {
            output += l2Out[i] * (int) l3Weights[i];
        }

        // Scale to centipawns
        return output / Math.max(1, outputScale);
    }

    public int getL1Size() { return l1Size; }
    public int getL2Size() { return l2Size; }
    public int getFeatureSize() { return featureSize; }

    /**
     * Load NNUE weights from a classpath resource.
     * Supports both raw and gzip-compressed (.gz) files.
     */
    public static NNUENetwork loadFromResource(String resourcePath) {
        try (InputStream raw = NNUENetwork.class.getResourceAsStream(resourcePath)) {
            if (raw == null) {
                log.warn("NNUE weights resource not found: {}", resourcePath);
                return null;
            }
            InputStream in = resourcePath.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            return load(in);
        } catch (IOException e) {
            log.warn("Failed to load NNUE weights from {}: {}", resourcePath, e.toString());
            return null;
        }
    }

    private static NNUENetwork load(InputStream rawInput) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(rawInput, 1 << 20))) {
            int version = readInt(in);
            if (version != EXPECTED_VERSION) {
                throw new IOException("NNUE weight version mismatch: got " + version + ", expected " + EXPECTED_VERSION);
            }
            int featureSize = readInt(in);
            int l1Size = readInt(in);
            int l2Size = readInt(in);

            log.info("Loading NNUE: features={}, L1={}, L2={}", featureSize, l1Size, l2Size);

            short[] l1Weights = readShorts(in, featureSize * l1Size);
            short[] l1Biases = readShorts(in, l1Size);
            short[] l2Weights = readShorts(in, l1Size * 2 * l2Size);
            short[] l2Biases = readShorts(in, l2Size);
            short[] l3Weights = readShorts(in, l2Size);
            short l3Bias = readShort(in);
            int outputScale = readShort(in);

            log.info("NNUE loaded: {} -> {} -> {} -> 1, outputScale={}", featureSize, l1Size, l2Size, outputScale);
            log.info("  L1 weights: {} shorts ({} MB)", l1Weights.length, l1Weights.length * 2 / 1048576);

            return new NNUENetwork(featureSize, l1Size, l2Size,
                    l1Weights, l1Biases, l2Weights, l2Biases, l3Weights, l3Bias, outputScale);
        }
    }

    private static int readInt(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static short readShort(DataInputStream in) throws IOException {
        return Short.reverseBytes(in.readShort());
    }

    private static short[] readShorts(DataInputStream in, int count) throws IOException {
        short[] arr = new short[count];
        for (int i = 0; i < count; i++) {
            arr[i] = Short.reverseBytes(in.readShort());
        }
        return arr;
    }
}
