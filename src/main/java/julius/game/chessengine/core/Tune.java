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
    public static int lmrBase = 75;
    public static int lmrDiv = 225;
    public static int lmrHistDiv = 6000;
    public static int aspDelta = 15;
    public static int rfpMargin = 75;
    public static int razorMargin = 220;
    public static int nmpEvalDiv = 200;
    public static int nmpDepthMargin = 15;
    public static int nmpBaseMargin = 150;
    public static int probcutMargin = 200;
    public static int histPrune = 1500;
    public static int futBase = 100;
    public static int futMul = 100;
    public static int seeQuiet = 25;
    public static int seeNoisy = 100;
    /** singular beta = ttScore - singularMul * depth / 10 */
    public static int singularMul = 20;
    public static int doubleExtMargin = 20;
    public static int qsFutility = 160;
    public static int bonusMul = 160;
    public static int bonusSub = 120;
    public static int bonusMax = 2000;

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
            default -> {
                return false;
            }
        }
        if (p.name().startsWith("lmr") && !p.name().equals("lmrHistDiv")) Searcher.initLmr();
        return true;
    }
}
