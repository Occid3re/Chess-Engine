package julius.game.chessengine.search;

import julius.game.chessengine.tuning.Tuning;

import java.util.function.IntUnaryOperator;

/**
 * Side-to-move perspective, centipawns everywhere.
 * Wire these from non-PV nodes in alpha-beta / PVS before LMR/LMP.
 */
public final class SearchPruning {

    private SearchPruning() {}

    private static int snmpMarginDepth1() { return Tuning.searchSnmpMarginDepth1(); }
    private static int snmpMarginDepth2() { return Tuning.searchSnmpMarginDepth2(); }
    private static int snmpMarginDepth3() { return Tuning.searchSnmpMarginDepth3(); }

    private static int razorMarginDepth1() { return Tuning.searchRazorMarginDepth1(); }
    private static int razorMarginDepth2() { return Tuning.searchRazorMarginDepth2(); }

    private static int probCutEnableDepth() { return Tuning.searchProbCutEnableDepth(); }
    private static int probCutMargin() { return Tuning.searchProbCutMargin(); }
    private static int probCutSeeMin() { return Tuning.searchProbCutSeeMin(); }

    private static int snmpMarginFor(int depth) {
        if (depth <= 1) return snmpMarginDepth1();
        if (depth == 2) return snmpMarginDepth2();
        return snmpMarginDepth3();
    }

    private static int razorMarginFor(int depth) {
        return depth <= 1 ? razorMarginDepth1() : razorMarginDepth2();
    }

    public static boolean tryStaticNullMovePrune(
            int depth, boolean inCheck, boolean isPvNode,
            int staticEvalCp, int alpha, int beta,
            PruneCallback cbIfPruned) {

        if (inCheck || isPvNode || depth > 3) return false;

        int margin = snmpMarginFor(depth);
        if (staticEvalCp - margin >= beta) {
            cbIfPruned.onPrune(staticEvalCp);
            return true;
        }
        return false;
    }

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

    public static boolean tryProbCut(
            int depth, boolean inCheck, boolean isPvNode,
            int alpha, int beta,
            MoveListGenerator gen,
            MovePredicate isCaptureOrPromotion,
            IntUnaryOperator seeCp,
            MakeMove make, UndoMove undo,
            ReducedSearch reducedSearch,
            PruneCallback cbIfPruned) {

        if (isPvNode || inCheck || depth < probCutEnableDepth()) return false;

        final int betaPrime = beta + probCutMargin();
        final int verifyAlpha = betaPrime - 1;
        final int verifyBeta = betaPrime;

        int[] moves = gen.generateMoves();
        if (moves.length == 0) return false;

        int w = 0;
        for (int i = 0; i < moves.length; i++) {
            int move = moves[i];
            if (isCaptureOrPromotion.test(move)) {
                moves[w++] = move;
            }
        }
        if (w == 0) return false;

        insertionSortBySeeDesc(moves, w, seeCp);

        for (int i = 0; i < w; i++) {
            int move = moves[i];
            if (seeCp.applyAsInt(move) < probCutSeeMin()) continue;

            make.doMove(move);
            int score = reducedSearch.search(depth - 2, verifyAlpha, verifyBeta);
            undo.undoMove();

            if (score >= betaPrime) {
                cbIfPruned.onPrune(score);
                return true;
            }
        }
        return false;
    }

    private static void insertionSortBySeeDesc(int[] moves, int len, IntUnaryOperator see) {
        for (int i = 1; i < len; i++) {
            int key = moves[i];
            int keySee = see.applyAsInt(key);
            int j = i - 1;
            while (j >= 0 && see.applyAsInt(moves[j]) < keySee) {
                moves[j + 1] = moves[j];
                j--;
            }
            moves[j + 1] = key;
        }
    }

    @FunctionalInterface public interface PruneCallback { void onPrune(int scoreCp); }
    @FunctionalInterface public interface Quiescence { int search(int alpha, int beta); }
    @FunctionalInterface public interface MoveListGenerator { int[] generateMoves(); }
    @FunctionalInterface public interface MovePredicate { boolean test(int move); }
    @FunctionalInterface public interface MakeMove { void doMove(int move); }
    @FunctionalInterface public interface UndoMove { void undoMove(); }
    @FunctionalInterface public interface ReducedSearch { int search(int depth, int alpha, int beta); }
}
