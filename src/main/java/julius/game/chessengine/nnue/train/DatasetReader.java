package julius.game.chessengine.nnue.train;

import julius.game.chessengine.ai.nnue.HalfKPFeature;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DatasetReader {
    private DatasetReader() {}

    public static final class Batch {
        public final int[][] featureIndices;
        public final int[] targetsCp;

        public Batch(int[][] featureIndices, int[] targetsCp) {
            this.featureIndices = featureIndices;
            this.targetsCp = targetsCp;
        }
    }

    public interface BatchConsumer { void accept(Batch b) throws IOException; }

    public static void forEachBatch(Path csv, int batchSize, BatchConsumer consumer) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(csv)) {
            String line = br.readLine(); // header
            List<int[]> feats = new ArrayList<>(batchSize);
            List<Integer> targets = new ArrayList<>(batchSize);
            while ((line = br.readLine()) != null) {
                TrainingSample sample = TrainingSample.fromCsvLine(line);
                BitBoard b = FEN.translateFENtoBitBoard(sample.fen());
                List<Integer> fi = HalfKPFeature.featuresFor(b);
                int[] arr = new int[fi.size()];
                for (int i = 0; i < fi.size(); i++) arr[i] = fi.get(i);
                feats.add(arr);
                targets.add(sample.evalCp());
                if (feats.size() == batchSize) {
                    emit(consumer, feats, targets);
                    feats.clear();
                    targets.clear();
                }
            }
            if (!feats.isEmpty()) {
                emit(consumer, feats, targets);
            }
        }
    }

    private static void emit(BatchConsumer consumer, List<int[]> feats, List<Integer> targets) throws IOException {
        int[][] fi = feats.toArray(new int[0][]);
        int[] t = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) t[i] = targets.get(i);
        consumer.accept(new Batch(fi, t));
    }
}
