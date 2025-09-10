package julius.game.chessengine.nnue.selfplay;

import julius.game.chessengine.board.FEN;
import julius.game.chessengine.engine.Engine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Runs self-play games using the engine and writes sampled positions to a CSV
 * file. This implementation is intentionally simple and runs games
 * sequentially in a single thread.
 */
public final class SelfPlayRunner {

    private SelfPlayRunner() {}

    public static void run(SelfPlayConfig cfg) throws IOException {
        Files.createDirectories(cfg.outCsv.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(cfg.outCsv,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write("fen,eval_cp\n");
            for (int g = 0; g < cfg.games; g++) {
                Engine engine = new Engine();
                for (int ply = 0; ply < cfg.maxPlies; ply++) {
                    if (engine.getGameState().isGameOver()) break;
                    if (ply % cfg.sampleEveryPly == 0) {
                        String fen = FEN.translateBoardToFEN(engine.getBitBoard()).getRenderBoard()
                                + (engine.whitesTurn() ? " w" : " b") + " - - 0 1";
                        int label = SelfPlaySearch.evaluateCp(engine, cfg.labelTimeMs);
                        if (label > 2000) label = 2000;
                        if (label < -2000) label = -2000;
                        w.write(fen + "," + label + "\n");
                    }
                    int move = SelfPlaySearch.bestMove(engine, cfg.moveTimeMs);
                    if (move == -1) break;
                    engine.performMove(move);
                }
            }
        }
    }
}
