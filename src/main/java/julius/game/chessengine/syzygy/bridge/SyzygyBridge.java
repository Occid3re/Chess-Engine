package julius.game.chessengine.syzygy.bridge;

/**
 * Shim that delegates to the canonical {@code syzygy.bridge.SyzygyBridge}
 * implementation used by the JNI layer. The native bridge keeps its
 * historical package name so that existing tablebase builds remain
 * compatible, while the engine code continues to depend on this class.
 */
public final class SyzygyBridge {

    private SyzygyBridge() {
    }

    public static boolean isLibLoaded() {
        return syzygy.bridge.SyzygyBridge.isLibLoaded();
    }

    public static boolean isAvailable(int piecesLeft) {
        return syzygy.bridge.SyzygyBridge.isAvailable(piecesLeft);
    }

    public static int load(String path) {
        return syzygy.bridge.SyzygyBridge.load(path);
    }

    public static int getSupportedSize() {
        return syzygy.bridge.SyzygyBridge.getSupportedSize();
    }

    public static int probeSyzygyWDL(long white, long black, long kings, long queens, long rooks,
                                     long bishops, long knights, long pawns, int ep, boolean turn) {
        return syzygy.bridge.SyzygyBridge.probeSyzygyWDL(white, black, kings, queens, rooks, bishops,
                knights, pawns, ep, turn);
    }

    public static int probeSyzygyDTZ(long white, long black, long kings, long queens, long rooks,
                                     long bishops, long knights, long pawns, int rule50, int ep,
                                     boolean turn) {
        return syzygy.bridge.SyzygyBridge.probeSyzygyDTZ(white, black, kings, queens, rooks, bishops,
                knights, pawns, rule50, ep, turn);
    }
}
