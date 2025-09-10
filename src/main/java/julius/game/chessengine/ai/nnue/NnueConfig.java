package julius.game.chessengine.ai.nnue;

public final class NnueConfig {
    private NnueConfig() {}

    public static boolean ENABLE_NNUE = true;
    public static double BLEND = 0.7; // NNUE vs classical
    public static String RESOURCE = "/weights/current.nnuev1";
}
