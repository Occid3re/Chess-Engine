package julius.game.chessengine.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Search parameters that SPSA tuning may change. Defaults are the hand-picked values; the engine reads the
 * static fields directly. With {@code -Dcore.tune=true} every parameter is offered as a UCI spin option
 * (same name), which is how cutechess-based tuners pass candidate values to the engine.
 */
public final class Tune {

    /** One tunable: current value via the field, plus range and the SPSA perturbation size {@code c}. */
    public record Param(String name, int defaultValue, int min, int max, double c) {
    }

    // LMR table: base and divisor, both x100 (0.75 + ln(d) * ln(m) / 2.25)
    // Values from SPSA (1500 iterations, 12000 games at 6+0.06, 2026-10-18); SPRT at 10+0.1 vs the previous
    // defaults: +28.3 Elo (248-162-648, 1058 games). spsa.py reads the defaults and ranges from this file.
    public static int lmrBase = 86;
    public static int lmrDiv = 221;
    public static int lmrHistDiv = 6867;
    public static int aspDelta = 10;
    public static int rfpMargin = 68;
    public static int razorMargin = 237;
    public static int nmpEvalDiv = 228;
    public static int nmpDepthMargin = 24;
    public static int nmpBaseMargin = 100;
    public static int probcutMargin = 190;
    public static int histPrune = 1346;
    public static int futBase = 99;
    public static int futMul = 100;
    public static int seeQuiet = 22;
    public static int seeNoisy = 103;
    /** singular beta = ttScore - singularMul * depth / 10 */
    public static int singularMul = 17;
    public static int doubleExtMargin = 22;
    public static int qsFutility = 180;
    public static int bonusMul = 166;
    public static int bonusSub = 139;
    public static int bonusMax = 2119;
    /**
     * Time management of increment / sudden-death games (SearchManager.allocate): the moves still to come at the
     * start (tmMtg) and their floor (tmMtgMin), the share of the increment spent per move (tmInc, %), and how much
     * the root position's uncertainty stretches the time for this move (tmUnc, %: factor 1 + tmUnc / 100 * (u / 0.04
     * - 1), clamped to 0.6 .. 1.6, u = the network's expected evaluation error as a win probability; 0 = off).
     */
    public static int tmMtg = 40;
    public static int tmMtgMin = 16;
    public static int tmInc = 80;
    public static int tmUnc = 0;
    /**
     * Uncertainty-aware reductions (off with uncDepth 0): at nodes with at least uncDepth plies left, the network's
     * uncertainty head rates the position; below uncLo / 1000 (the network is sure) late moves are reduced one ply
     * more, above uncHi / 1000 (unclear) one ply less. Needs core/nnue-unc.bin for the loaded network.
     */
    public static int uncDepth = 0;
    public static int uncLo = 25;
    public static int uncHi = 70;

    private static final Map<String, Param> PARAMS = new LinkedHashMap<>();

    static {
        add("lmrBase", lmrBase, 0, 200, 10);
        add("lmrDiv", lmrDiv, 120, 400, 15);
        add("lmrHistDiv", lmrHistDiv, 2000, 16000, 600);
        add("aspDelta", aspDelta, 5, 50, 3);
        add("rfpMargin", rfpMargin, 30, 150, 6);
        add("razorMargin", razorMargin, 100, 500, 20);
        add("nmpEvalDiv", nmpEvalDiv, 100, 400, 20);
        add("nmpDepthMargin", nmpDepthMargin, 0, 40, 3);
        add("nmpBaseMargin", nmpBaseMargin, 0, 300, 15);
        add("probcutMargin", probcutMargin, 100, 400, 15);
        add("histPrune", histPrune, 500, 4000, 150);
        add("futBase", futBase, 40, 250, 10);
        add("futMul", futMul, 40, 250, 10);
        add("seeQuiet", seeQuiet, 5, 80, 4);
        add("seeNoisy", seeNoisy, 40, 250, 10);
        add("singularMul", singularMul, 5, 50, 3);
        add("doubleExtMargin", doubleExtMargin, 5, 60, 4);
        add("qsFutility", qsFutility, 50, 400, 15);
        add("bonusMul", bonusMul, 50, 400, 15);
        add("bonusSub", bonusSub, 0, 300, 15);
        add("bonusMax", bonusMax, 800, 4000, 150);
        add("tmMtg", tmMtg, 20, 80, 4);
        add("tmMtgMin", tmMtgMin, 8, 40, 2);
        add("tmInc", tmInc, 40, 100, 5);
        add("tmUnc", tmUnc, 0, 100, 8);
        add("uncDepth", uncDepth, 0, 20, 1);
        add("uncLo", uncLo, 0, 100, 4);
        add("uncHi", uncHi, 20, 200, 6);
    }

    private Tune() {
    }

    private static void add(String name, int value, int min, int max, double c) {
        PARAMS.put(name.toLowerCase(), new Param(name, value, min, max, c));
    }

    public static boolean enabled() {
        return Boolean.getBoolean("core.tune");
    }

    public static Iterable<Param> params() {
        return PARAMS.values();
    }

    /** Sets a parameter by (case-insensitive) name; returns false for unknown names. */
    public static boolean set(String name, int value) {
        Param p = PARAMS.get(name.toLowerCase());
        if (p == null) return false;
        int v = Math.max(p.min(), Math.min(p.max(), value));
        switch (p.name()) {
            case "lmrBase" -> lmrBase = v;
            case "lmrDiv" -> lmrDiv = v;
            case "lmrHistDiv" -> lmrHistDiv = v;
            case "aspDelta" -> aspDelta = v;
            case "rfpMargin" -> rfpMargin = v;
            case "razorMargin" -> razorMargin = v;
            case "nmpEvalDiv" -> nmpEvalDiv = v;
            case "nmpDepthMargin" -> nmpDepthMargin = v;
            case "nmpBaseMargin" -> nmpBaseMargin = v;
            case "probcutMargin" -> probcutMargin = v;
            case "histPrune" -> histPrune = v;
            case "futBase" -> futBase = v;
            case "futMul" -> futMul = v;
            case "seeQuiet" -> seeQuiet = v;
            case "seeNoisy" -> seeNoisy = v;
            case "singularMul" -> singularMul = v;
            case "doubleExtMargin" -> doubleExtMargin = v;
            case "qsFutility" -> qsFutility = v;
            case "bonusMul" -> bonusMul = v;
            case "bonusSub" -> bonusSub = v;
            case "bonusMax" -> bonusMax = v;
            case "tmMtg" -> tmMtg = v;
            case "tmMtgMin" -> tmMtgMin = v;
            case "tmInc" -> tmInc = v;
            case "tmUnc" -> tmUnc = v;
            case "uncDepth" -> uncDepth = v;
            case "uncLo" -> uncLo = v;
            case "uncHi" -> uncHi = v;
            default -> {
                return false;
            }
        }
        if (p.name().startsWith("lmr") && !p.name().equals("lmrHistDiv")) Searcher.initLmr();
        return true;
    }
}
