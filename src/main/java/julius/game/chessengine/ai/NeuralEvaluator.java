package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;

/**
 * Lightweight feed-forward neural network used to evaluate board positions.
 * This implementation is intentionally small and self-contained. The network
 * layout is fixed to 12×64 inputs (one plane per piece type) followed by a
 * single hidden layer and a single output neuron returning a centipawn-like
 * score from White's perspective.
 */
public class NeuralEvaluator {

    private static final int INPUT_SIZE = 12 * 64;
    private static final int HIDDEN_SIZE = 64;

    private final double[][] w1 = new double[HIDDEN_SIZE][INPUT_SIZE];
    private final double[] b1 = new double[HIDDEN_SIZE];
    private final double[] w2 = new double[HIDDEN_SIZE];
    private double b2;

    public NeuralEvaluator() {
        // Deterministic random seed so behaviour is stable across runs.
        java.util.Random rnd = new java.util.Random(42);
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            for (int i = 0; i < INPUT_SIZE; i++) {
                w1[j][i] = rnd.nextGaussian() * 0.01;
            }
            b1[j] = 0.0;
            w2[j] = rnd.nextGaussian() * 0.01;
        }
        b2 = 0.0;
    }

    /**
     * Encodes the given board and evaluates it using the neural network.
     * The returned value represents a centipawn-like score from White's
     * perspective (positive scores favour White, negative scores favour Black).
     */
    public double evaluate(BitBoard board) {
        double[] input = encode(board);
        double[] hidden = new double[HIDDEN_SIZE];
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            double sum = b1[j];
            for (int i = 0; i < INPUT_SIZE; i++) {
                sum += w1[j][i] * input[i];
            }
            // ReLU activation
            hidden[j] = Math.max(0.0, sum);
        }
        double out = b2;
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            out += w2[j] * hidden[j];
        }
        // Scale to typical centipawn magnitude
        return out * 100.0;
    }

    /**
     * Converts the bitboard representation into 12×64 planes used as network
     * input. Piece order: white Pawns, Knights, Bishops, Rooks, Queens, King,
     * then the same order for black pieces.
     */
    private double[] encode(BitBoard board) {
        double[] input = new double[INPUT_SIZE];
        int plane = 0;
        plane = fillPlane(board.getWhitePawns(), plane, input);
        plane = fillPlane(board.getWhiteKnights(), plane, input);
        plane = fillPlane(board.getWhiteBishops(), plane, input);
        plane = fillPlane(board.getWhiteRooks(), plane, input);
        plane = fillPlane(board.getWhiteQueens(), plane, input);
        plane = fillPlane(board.getWhiteKing(), plane, input);
        plane = fillPlane(board.getBlackPawns(), plane, input);
        plane = fillPlane(board.getBlackKnights(), plane, input);
        plane = fillPlane(board.getBlackBishops(), plane, input);
        plane = fillPlane(board.getBlackRooks(), plane, input);
        plane = fillPlane(board.getBlackQueens(), plane, input);
        fillPlane(board.getBlackKing(), plane, input);
        return input;
    }

    private int fillPlane(long bitboard, int planeIndex, double[] input) {
        long bb = bitboard;
        int offset = planeIndex * 64;
        for (int sq = 0; sq < 64; sq++) {
            if ((bb & 1L) != 0) {
                input[offset + sq] = 1.0;
            }
            bb >>>= 1;
        }
        return planeIndex + 1;
    }
}
