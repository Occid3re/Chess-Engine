package julius.game.chessengine.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluation weights. Every weight is a packed (midgame, endgame) pair, see {@link #S(int, int)}.
 * All terms are linear in these weights so they can be fitted with gradient descent (see core.tuning).
 */
public final class EvalParams {

    public static final String RESOURCE = "/core/eval-weights.txt";

    private static final List<String> NAMES = new ArrayList<>();
    private static final List<Integer> SIZES = new ArrayList<>();
    private static int next = 0;

    private static int block(String name, int size) {
        int start = next;
        NAMES.add(name);
        SIZES.add(size);
        next += size;
        return start;
    }

    public static final int MATERIAL = block("material", 6);
    public static final int PST = block("pst", 6 * 64);
    public static final int MOB_KNIGHT = block("mobKnight", 9);
    public static final int MOB_BISHOP = block("mobBishop", 14);
    public static final int MOB_ROOK = block("mobRook", 15);
    public static final int MOB_QUEEN = block("mobQueen", 28);
    public static final int DOUBLED = block("doubled", 1);
    public static final int ISOLATED = block("isolated", 1);
    public static final int BACKWARD = block("backward", 1);
    public static final int SUPPORTED = block("supported", 8);
    public static final int PHALANX = block("phalanx", 8);
    public static final int PASSED = block("passed", 8);
    public static final int PASSED_FREE = block("passedFree", 8);
    public static final int PASSED_KDIST_OWN = block("passedKingDistOwn", 8);
    public static final int PASSED_KDIST_THEIR = block("passedKingDistTheir", 8);
    public static final int PASSED_BLOCKED = block("passedBlocked", 8);
    public static final int BISHOP_PAIR = block("bishopPair", 1);
    public static final int BISHOP_PAWNS = block("bishopPawns", 1);
    public static final int ROOK_OPEN = block("rookOpen", 1);
    public static final int ROOK_SEMI = block("rookSemiOpen", 1);
    public static final int OUTPOST = block("outpost", 2);
    public static final int MINOR_BEHIND_PAWN = block("minorBehindPawn", 1);
    public static final int SHELTER = block("shelter", 8);
    public static final int STORM = block("storm", 8);
    public static final int KING_DANGER = block("kingDanger", 40);
    public static final int THREAT_BY_PAWN = block("threatByPawn", 6);
    public static final int THREAT_BY_MINOR = block("threatByMinor", 6);
    public static final int THREAT_BY_ROOK = block("threatByRook", 6);
    public static final int HANGING = block("hanging", 1);
    public static final int PAWN_PUSH_THREAT = block("pawnPushThreat", 1);
    public static final int TEMPO = block("tempo", 1);

    public static final int COUNT = next;

    /** The active weights (packed). */
    public static final int[] W = new int[COUNT];

    public static int S(int mg, int eg) {
        return (mg << 16) + eg;
    }

    public static int mg(int s) {
        return (s + 0x8000) >> 16;
    }

    public static int eg(int s) {
        return (short) s;
    }

    static {
        setDefaults();
        String override = System.getProperty("core.evalFile");
        try {
            if (override != null && !override.isBlank()) {
                load(Files.readString(Path.of(override)));
            } else {
                try (InputStream in = EvalParams.class.getResourceAsStream(RESOURCE)) {
                    if (in != null) load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load evaluation weights", e);
        }
    }

    private EvalParams() {
    }

    public static void setDefaults() {
        java.util.Arrays.fill(W, 0);
        int[] mat = {S(100, 120), S(320, 300), S(330, 320), S(500, 520), S(950, 950), 0};
        System.arraycopy(mat, 0, W, MATERIAL, 6);
        W[BISHOP_PAIR] = S(30, 50);
        W[TEMPO] = S(15, 5);
        for (int i = 0; i < 8; i++) W[PASSED + i] = S(i * 5, i * i * 3);
    }

    /** Parses "name index mg eg" lines; unknown names are ignored so weight files survive layout changes. */
    public static void load(String text) throws IOException {
        try (BufferedReader r = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                int b = NAMES.indexOf(t[0]);
                if (b < 0) continue;
                int idx = Integer.parseInt(t[1]);
                if (idx >= SIZES.get(b)) continue;
                W[offsetOf(b) + idx] = S(Integer.parseInt(t[2]), Integer.parseInt(t[3]));
            }
        }
    }

    private static int offsetOf(int block) {
        int off = 0;
        for (int i = 0; i < block; i++) off += SIZES.get(i);
        return off;
    }

    public static void save(int[] weights, PrintWriter out) {
        int off = 0;
        for (int b = 0; b < NAMES.size(); b++) {
            for (int i = 0; i < SIZES.get(b); i++) {
                int s = weights[off + i];
                out.println(NAMES.get(b) + " " + i + " " + mg(s) + " " + eg(s));
            }
            off += SIZES.get(b);
        }
    }

    public static String nameOf(int index) {
        int off = 0;
        for (int b = 0; b < NAMES.size(); b++) {
            if (index < off + SIZES.get(b)) return NAMES.get(b) + "[" + (index - off) + "]";
            off += SIZES.get(b);
        }
        return "?";
    }
}
