package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Micro-benchmark for manual performance inspection")
class KingSafetyBackrankWeaknessMicroBenchmark {

    @Test
    void measureBackrankWeaknessHotPath() {
        BitBoard board = FEN.translateFENtoBitBoard("4k1r1/5q2/2b2n2/8/8/6N1/5P1P/6K1 w - - 0 1");
        KingSafetyModule module = new KingSafetyModule();
        module.evaluate(board);

        int iterations = 200_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            module.evaluate(board);
        }
        long elapsed = System.nanoTime() - start;
        double nanosPer = (double) elapsed / iterations;
        System.out.printf("Backrank weakness eval: %.2f ns/op%n", nanosPer);
    }
}

