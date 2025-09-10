package julius.game.chessengine.nnue.selfplay;

public final class DumpPositions {
    public static void main(String[] args) throws Exception {
        SelfPlayConfig cfg = new SelfPlayConfig();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--games" -> cfg.games = Integer.parseInt(args[++i]);
                case "--moveTimeMs" -> cfg.moveTimeMs = Integer.parseInt(args[++i]);
                case "--labelTimeMs" -> cfg.labelTimeMs = Integer.parseInt(args[++i]);
                case "--sampleEveryPly" -> cfg.sampleEveryPly = Integer.parseInt(args[++i]);
                case "--maxPlies" -> cfg.maxPlies = Integer.parseInt(args[++i]);
                case "--threads" -> cfg.threads = Integer.parseInt(args[++i]);
                case "--out" -> cfg.outCsv = java.nio.file.Paths.get(args[++i]);
                case "--randomizeStart" -> cfg.randomizeStart = Boolean.parseBoolean(args[++i]);
                default -> {
                }
            }
        }
        SelfPlayRunner.run(cfg);
    }
}
