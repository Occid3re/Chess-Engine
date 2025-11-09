package julius.game.chessengine.search;

import java.util.function.IntUnaryOperator;

import julius.game.chessengine.tuning.Tuning;

/**
 * Side-to-move perspective, centipawns everywhere.
 * Wire these from non-PV nodes in alpha-beta / PVS before LMR/LMP.
 */
public final class SearchPruning {

    private SearchPruning() {}

    /* -------------------------
     * Tunable margins (centipawns)
     * Values are sourced from {@link Tuning} so the auto-tuner can adjust them.
     * ------------------------- */
    public static int snmpMarginDepth1() { return Tuning.searchSnmpMarginDepth1(); }
    public static int snmpMarginDepth2() { return Tuning.searchSnmpMarginDepth2(); }
    public static int snmpMarginDepth3() { return Tuning.searchSnmpMarginDepth3(); }

    public static int razorMargin1()     { return Tuning.searchRazorMarginDepth1(); }
    public static int razorMargin2()     { return Tuning.searchRazorMarginDepth2(); }

    public static int probCutEnableDepth() { return Tuning.searchProbCutEnableDepth(); }
    public static int probCutMargin()      { return Tuning.searchProbCutMargin(); }
    public static int probCutSeeMin()      { return Tuning.searchProbCutSeeMin(); }

    private static int snmpMarginFor(int depth) {
        if (depth <= 1) return snmpMarginDepth1();
        if (depth == 2) return snmpMarginDepth2();
        return snmpMarginDepth3();
    }

    private static int razorMarginFor(int depth) {
        return (depth <= 1 ? razorMargin1() : razorMargin2());
    }

    /* -------------------------
     * Static Null-Move Pruning
     * ------------------------- */
    public static boolean tryStaticNullMovePrune(
            int depth, boolean inCheck, boolean isPvNode,
            int staticEvalCp, int alpha, int beta,
            PruneCallback cbIfPruned) {

        if (inCheck || isPvNode || depth > 3) return false;

        int m = snmpMarginFor(depth);
        // Side-to-move perspective: prune if statEval - margin >= beta (clear fail-high)
        if (staticEvalCp - m >= beta) {
            cbIfPruned.onPrune(staticEvalCp);
            return true;
        }
        return false;
    }

    /* -------------------------
     * Razoring (d <= 2)
     * ------------------------- */
    public static boolean tryRazoring(
            int depth, boolean inCheck, boolean isPvNode,
            int staticEvalCp, int alpha, int beta,
            Quiescence qsearch, PruneCallback cbIfPruned) {

        if (inCheck || isPvNode || depth > 2) return false;

        int margin = razorMarginFor(depth);
        if (staticEvalCp + margin <= alpha) {
            int qs = qsearch.search(alpha, beta);
            if (qs <= alpha) {
                cbIfPruned.onPrune(qs);
                return true;
            }
        }
        return false;
    }

    /* -------------------------
     * ProbCut (captures-only verification)
     * Apply in non-PV, not-in-check nodes.
     * ------------------------- */
    public static boolean tryProbCut(
            int depth, boolean inCheck, boolean isPvNode,
            int alpha, int beta,
            MoveListGenerator gen, // returns a packed int[] of moves
            MovePredicate isCaptureOrPromotion,
            IntUnaryOperator seeCp, // SEE in centipawns
            MakeMove make, UndoMove undo,
            ReducedSearch reducedSearch, // search(depth-2, a, b)
            PruneCallback cbIfPruned) {

        if (isPvNode || inCheck || depth < probCutEnableDepth()) return false;

        final int betaPrime = beta + probCutMargin();    // tighter, above beta
        final int verifyAlpha = betaPrime - 1;           // PVS-style window
        final int verifyBeta  = betaPrime;

        int[] moves = gen.generateMoves();
        if (moves.length == 0) return false;

        // Lightweight ordering: prefer higher SEE first among captures/promotions
        // Keep array compact by moving non-capture/promotions to the end.
        int w = 0;
        for (int i = 0; i < moves.length; i++) {
            int m = moves[i];
            if (isCaptureOrPromotion.test(m)) {
                moves[w++] = m;
            }
        }
        if (w == 0) return false;

        // Sort first w moves by SEE descending (simple insertion sort is fine for small w)
        insertionSortBySeeDesc(moves, w, seeCp);

        for (int i = 0; i < w; i++) {
            int move = moves[i];
            if (seeCp.applyAsInt(move) < probCutSeeMin()) continue;

            make.doMove(move);
            int score = reducedSearch.search(depth - 2, verifyAlpha, verifyBeta);
            undo.undoMove();

            // If even the reduced search already exceeds β', prune
            if (score >= betaPrime) {
                cbIfPruned.onPrune(score);
                return true;
            }
        }
        return false;
    }

    private static void insertionSortBySeeDesc(int[] a, int len, IntUnaryOperator see) {
        for (int i = 1; i < len; i++) {
            int key = a[i];
            int keySee = see.applyAsInt(key);
            int j = i - 1;
            while (j >= 0 && see.applyAsInt(a[j]) < keySee) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = key;
        }
    }

    /* --- plumbing interfaces (keep them tiny) --- */
    @FunctionalInterface public interface PruneCallback { void onPrune(int scoreCp); }
    @FunctionalInterface public interface Quiescence { int search(int alpha, int beta); }
    @FunctionalInterface public interface MoveListGenerator { int[] generateMoves(); }
    @FunctionalInterface public interface MovePredicate { boolean test(int move); }
    @FunctionalInterface public interface MakeMove { void doMove(int move); }
    @FunctionalInterface public interface UndoMove { void undoMove(); }
    @FunctionalInterface public interface ReducedSearch { int search(int depth, int alpha, int beta); }
}
