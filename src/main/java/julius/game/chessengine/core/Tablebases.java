package julius.game.chessengine.core;

import julius.game.chessengine.syzygy.bridge.SyzygyBridge;
import julius.game.chessengine.syzygy.bridge.SyzygyConstants;

/**
 * Thin adapter over the Fathom JNI bridge. WDL probes are used inside the search, the DTZ root probe
 * picks the move at the root when the position is in the tables.
 */
public final class Tablebases {

    public static final int FAIL = Integer.MIN_VALUE;

    private final int largest;

    private Tablebases(int largest) {
        this.largest = largest;
    }

    /** Loads tables from a path list; returns null if the native library or the tables are unavailable. */
    public static Tablebases load(String paths, java.util.function.Consumer<String> log) {
        if (paths == null || paths.isBlank() || paths.equalsIgnoreCase("<empty>")) return null;
        try {
            if (!SyzygyBridge.isLibLoaded()) {
                log.accept("info string Syzygy native library not available");
                return null;
            }
            // Expands a root folder into the subfolders that actually contain table files.
            String resolved = expandPaths(paths.trim());
            int n = SyzygyBridge.load(resolved);
            if (n <= 0) {
                log.accept("info string Syzygy tables not found in " + paths);
                return null;
            }
            log.accept("info string Syzygy tablebases loaded, up to " + n + " pieces");
            return new Tablebases(n);
        } catch (Throwable t) {
            log.accept("info string Syzygy unavailable: " + t);
            return null;
        }
    }

    /** Expands each listed folder into itself plus subfolders (two levels) that contain .rtbw/.rtbz files. */
    static String expandPaths(String paths) {
        java.util.LinkedHashSet<String> dirs = new java.util.LinkedHashSet<>();
        for (String token : paths.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            java.nio.file.Path base = java.nio.file.Path.of(token.trim().replace("\"", ""));
            if (!java.nio.file.Files.isDirectory(base)) continue;
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(base, 2)) {
                walk.filter(java.nio.file.Files::isDirectory).filter(Tablebases::hasTables)
                        .forEach(d -> dirs.add(d.toAbsolutePath().normalize().toString()));
            } catch (java.io.IOException ignored) {
                // unreadable folder: skip
            }
        }
        return dirs.isEmpty() ? paths : String.join(java.io.File.pathSeparator, dirs);
    }

    private static boolean hasTables(java.nio.file.Path dir) {
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().toLowerCase().contains(".rtb"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public int largest() {
        return largest;
    }

    /** +1 win, 0 draw (including cursed/blessed), -1 loss for the side to move; FAIL if not probeable. */
    public int probeWdl(Position p) {
        if (p.castling != 0 || p.halfmove != 0 || Long.bitCount(p.all) > largest) return FAIL;
        int r = SyzygyBridge.probeSyzygyWDL(p.occ[0], p.occ[1], p.typeBB(Position.KING), p.typeBB(Position.QUEEN),
                p.typeBB(Position.ROOK), p.typeBB(Position.BISHOP), p.typeBB(Position.KNIGHT), p.typeBB(Position.PAWN),
                p.ep >= 0 ? p.ep : 0, p.side == Position.WHITE);
        return switch (r) {
            case SyzygyConstants.TB_WIN -> 1;
            case SyzygyConstants.TB_LOSS -> -1;
            case SyzygyConstants.TB_DRAW, SyzygyConstants.TB_CURSED_WIN, SyzygyConstants.TB_BLESSED_LOSS -> 0;
            default -> FAIL;
        };
    }

    /**
     * Root probe: returns {move, wdl} where wdl is +1/0/-1 for the side to move, or null if unavailable.
     */
    public int[] probeRoot(Position p) {
        if (p.castling != 0 || Long.bitCount(p.all) > largest) return null;
        int r = SyzygyBridge.probeSyzygyDTZ(p.occ[0], p.occ[1], p.typeBB(Position.KING), p.typeBB(Position.QUEEN),
                p.typeBB(Position.ROOK), p.typeBB(Position.BISHOP), p.typeBB(Position.KNIGHT), p.typeBB(Position.PAWN),
                p.halfmove, p.ep >= 0 ? p.ep : 0, p.side == Position.WHITE);
        if (r == SyzygyConstants.TB_RESULT_FAILED) return null;
        int wdl = SyzygyConstants.winDrawLoss(r);
        int from = SyzygyConstants.fromSquare(r);
        int to = SyzygyConstants.toSquare(r);
        int promo = SyzygyConstants.promoteInto(r);
        if (from == to) return null;
        int[] moves = new int[256];
        int n = MoveGen.generateAll(p, moves, 0);
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (Move.from(m) != from || Move.to(m) != to || !p.isLegal(m)) continue;
            int pt = Move.promotionType(m);
            int want = switch (promo) {
                case SyzygyConstants.TB_PROMOTES_QUEEN -> Position.QUEEN;
                case SyzygyConstants.TB_PROMOTES_ROOK -> Position.ROOK;
                case SyzygyConstants.TB_PROMOTES_BISHOP -> Position.BISHOP;
                case SyzygyConstants.TB_PROMOTES_KNIGHT -> Position.KNIGHT;
                default -> -1;
            };
            if (pt != want) continue;
            int w = wdl == SyzygyConstants.TB_WIN ? 1 : wdl == SyzygyConstants.TB_LOSS ? -1 : 0;
            return new int[]{m, w};
        }
        return null;
    }
}
