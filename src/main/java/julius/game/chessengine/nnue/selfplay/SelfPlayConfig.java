package julius.game.chessengine.nnue.selfplay;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class SelfPlayConfig {
    public int games = 1000;
    public int maxPlies = 300;
    public int sampleEveryPly = 2;
    public int moveTimeMs = 50;
    public int labelTimeMs = 200;
    public int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    public boolean randomizeStart = true;
    public long seed = 7L;
    public Path outCsv = Paths.get("data/positions.csv");
}
