package julius.game.chessengine.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Replay buffer used to store self-play game data for training the neural evaluator.
 */
public class SelfPlayBuffer {

    /** Single training sample consisting of an encoded board state, the move made and the final game outcome. */
    public static class Sample {
        public final double[] state;
        public final int move;
        public final double outcome;

        public Sample(double[] state, int move, double outcome) {
            this.state = state;
            this.move = move;
            this.outcome = outcome;
        }
    }

    private final List<Sample> buffer = Collections.synchronizedList(new ArrayList<>());
    private final int capacity;

    public SelfPlayBuffer(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Adds all states from a finished game into the buffer.
     *
     * @param states  encoded board states for each move in the game
     * @param moves   moves played corresponding to the states
     * @param outcome final result from White's perspective (1=win, -1=loss, 0=draw)
     */
    public void addGame(List<double[]> states, List<Integer> moves, double outcome) {
        synchronized (buffer) {
            for (int i = 0; i < states.size(); i++) {
                if (buffer.size() >= capacity) {
                    buffer.remove(0);
                }
                buffer.add(new Sample(states.get(i), moves.get(i), outcome));
            }
        }
    }

    /** Samples a random batch from the buffer. */
    public List<Sample> sample(int batchSize) {
        List<Sample> batch = new ArrayList<>(batchSize);
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return batch;
            }
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < batchSize; i++) {
                batch.add(buffer.get(rnd.nextInt(buffer.size())));
            }
        }
        return batch;
    }

    public int size() {
        return buffer.size();
    }
}

