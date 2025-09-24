package julius.game.chessengine.ai;

import julius.game.chessengine.board.MoveHelper;

import java.util.Arrays;

final class Heuristics {
    private static final int BOARD_SQUARES = 64;
    private static final int HISTORY_SIZE = BOARD_SQUARES * BOARD_SQUARES;
    private static final int NUM_KILLER_MOVES = 2;

    int[][] killers;
    final int[][] history;
    final int[][] continuation;
    final int[][] counter;

    private boolean[] killerDirty;
    private int[] killerDirtyList;
    private int killerDirtyCount;

    private final int[] historyDelta;
    private final boolean[] historyDirty;
    private final int[] historyDirtyList;
    private int historyDirtyCount;

    private final int[] continuationDelta;
    private final boolean[] continuationDirty;
    private final int[] continuationDirtyList;
    private int continuationDirtyCount;

    private final int[] counterUpdates;
    private final boolean[] counterDirty;
    private final int[] counterDirtyList;
    private int counterDirtyCount;

    private long preparedTaskId = Long.MIN_VALUE;
    private int preparedDepth = -1;

    Heuristics(int depth) {
        this.killers = allocateKillers(Math.max(1, depth));
        this.history = new int[BOARD_SQUARES][BOARD_SQUARES];
        this.continuation = new int[BOARD_SQUARES][BOARD_SQUARES];
        this.counter = new int[BOARD_SQUARES][BOARD_SQUARES];
        for (int f = 0; f < BOARD_SQUARES; f++) {
            Arrays.fill(counter[f], -1);
        }
        this.killerDirty = new boolean[Math.max(1, depth)];
        this.killerDirtyList = new int[Math.max(1, depth)];
        this.historyDelta = new int[HISTORY_SIZE];
        this.historyDirty = new boolean[HISTORY_SIZE];
        this.historyDirtyList = new int[HISTORY_SIZE];
        this.continuationDelta = new int[HISTORY_SIZE];
        this.continuationDirty = new boolean[HISTORY_SIZE];
        this.continuationDirtyList = new int[HISTORY_SIZE];
        this.counterUpdates = new int[HISTORY_SIZE];
        Arrays.fill(counterUpdates, -1);
        this.counterDirty = new boolean[HISTORY_SIZE];
        this.counterDirtyList = new int[HISTORY_SIZE];
    }

    private static int[][] allocateKillers(int depth) {
        int[][] table = new int[depth][NUM_KILLER_MOVES];
        for (int i = 0; i < depth; i++) {
            Arrays.fill(table[i], -1);
        }
        return table;
    }

    void ensureCapacity(int depth) {
        if (depth <= killers.length) {
            return;
        }
        int[][] expanded = allocateKillers(depth);
        for (int i = 0; i < killers.length; i++) {
            System.arraycopy(killers[i], 0, expanded[i], 0, killers[i].length);
        }
        killers = expanded;
        killerDirty = Arrays.copyOf(killerDirty, depth);
        killerDirtyList = Arrays.copyOf(killerDirtyList, depth);
    }

    void beginIteration(Heuristics base, int requiredDepth) {
        resetUpdates();
        ensureCapacity(requiredDepth);
        base.ensureCapacity(requiredDepth);
        int limit = Math.min(requiredDepth, base.killers.length);
        for (int d = 0; d < limit; d++) {
            System.arraycopy(base.killers[d], 0, killers[d], 0, NUM_KILLER_MOVES);
        }
        for (int f = 0; f < BOARD_SQUARES; f++) {
            System.arraycopy(base.history[f], 0, history[f], 0, BOARD_SQUARES);
            System.arraycopy(base.continuation[f], 0, continuation[f], 0, BOARD_SQUARES);
            System.arraycopy(base.counter[f], 0, counter[f], 0, BOARD_SQUARES);
        }
    }

    boolean isPreparedFor(long taskId, int depth) {
        return preparedTaskId == taskId && preparedDepth == depth;
    }

    void markPrepared(long taskId, int depth) {
        this.preparedTaskId = taskId;
        this.preparedDepth = depth;
    }

    void resetUpdates() {
        for (int i = 0; i < killerDirtyCount; i++) {
            killerDirty[killerDirtyList[i]] = false;
        }
        killerDirtyCount = 0;

        for (int i = 0; i < historyDirtyCount; i++) {
            int idx = historyDirtyList[i];
            historyDirty[idx] = false;
            historyDelta[idx] = 0;
        }
        historyDirtyCount = 0;

        for (int i = 0; i < continuationDirtyCount; i++) {
            int idx = continuationDirtyList[i];
            continuationDirty[idx] = false;
            continuationDelta[idx] = 0;
        }
        continuationDirtyCount = 0;

        for (int i = 0; i < counterDirtyCount; i++) {
            int idx = counterDirtyList[i];
            counterDirty[idx] = false;
            counterUpdates[idx] = -1;
        }
        counterDirtyCount = 0;
        preparedTaskId = Long.MIN_VALUE;
        preparedDepth = -1;
    }

    boolean hasUpdates() {
        return killerDirtyCount > 0 || historyDirtyCount > 0 || continuationDirtyCount > 0 || counterDirtyCount > 0;
    }

    void recordKiller(int depth, int move) {
        if (move == -1) {
            return;
        }
        int depthIndex = Math.max(0, Math.min(depth, killers.length - 1));
        int[] row = killers[depthIndex];
        for (int j : row) {
            if (j == move) {
                return;
            }
        }
        for (int i = row.length - 1; i > 0; i--) {
            row[i] = row[i - 1];
        }
        row[0] = move;
        if (!killerDirty[depthIndex]) {
            killerDirty[depthIndex] = true;
            killerDirtyList[killerDirtyCount++] = depthIndex;
        }
    }

    void addHistory(int move, int depth) {
        if (move == -1 || MoveHelper.isCapture(move)) {
            return;
        }
        int from = move & 0x3F;
        int to = (move >>> 6) & 0x3F;
        int delta = depth * depth;
        history[from][to] += delta;
        int idx = (from << 6) | to;
        if (!historyDirty[idx]) {
            historyDirty[idx] = true;
            historyDirtyList[historyDirtyCount++] = idx;
        }
        historyDelta[idx] += delta;
    }

    void addContinuation(int prevMove, int move, int depth) {
        if (move == -1 || prevMove < 0) {
            return;
        }
        if (MoveHelper.isCapture(move) || MoveHelper.isPawnPromotionMove(move)) {
            return;
        }
        int prevTo = (prevMove >>> 6) & 0x3F;
        int to = (move >>> 6) & 0x3F;
        int delta = depth * depth;
        continuation[prevTo][to] += delta;
        int idx = (prevTo << 6) | to;
        if (!continuationDirty[idx]) {
            continuationDirty[idx] = true;
            continuationDirtyList[continuationDirtyCount++] = idx;
        }
        continuationDelta[idx] += delta;
    }

    void recordCounterMove(int prevMove, int move) {
        if (prevMove < 0) {
            return;
        }
        int pf = prevMove & 0x3F;
        int pt = (prevMove >>> 6) & 0x3F;
        counter[pf][pt] = move;
        int idx = (pf << 6) | pt;
        if (!counterDirty[idx]) {
            counterDirty[idx] = true;
            counterDirtyList[counterDirtyCount++] = idx;
        }
        counterUpdates[idx] = move;
    }

    void mergeInto(Heuristics target) {
        for (int i = 0; i < killerDirtyCount; i++) {
            int depth = killerDirtyList[i];
            target.ensureCapacity(depth + 1);
            int[] row = killers[depth];
            for (int move : row) {
                if (move != -1) {
                    target.insertKiller(depth, move);
                }
            }
        }
        for (int i = 0; i < historyDirtyCount; i++) {
            int idx = historyDirtyList[i];
            int from = idx >>> 6;
            int to = idx & 0x3F;
            target.history[from][to] += historyDelta[idx];
        }
        for (int i = 0; i < continuationDirtyCount; i++) {
            int idx = continuationDirtyList[i];
            int from = idx >>> 6;
            int to = idx & 0x3F;
            target.continuation[from][to] += continuationDelta[idx];
        }
        for (int i = 0; i < counterDirtyCount; i++) {
            int idx = counterDirtyList[i];
            int from = idx >>> 6;
            int to = idx & 0x3F;
            target.counter[from][to] = counterUpdates[idx];
        }
    }

    void insertKiller(int depth, int move) {
        if (move == -1) {
            return;
        }
        int depthIndex = Math.max(0, Math.min(depth, killers.length - 1));
        int[] row = killers[depthIndex];
        for (int j : row) {
            if (j == move) {
                return;
            }
        }
        for (int i = row.length - 1; i > 0; i--) {
            row[i] = row[i - 1];
        }
        row[0] = move;
    }

    void decayHistory() {
        for (int f = 0; f < BOARD_SQUARES; f++) {
            for (int t = 0; t < BOARD_SQUARES; t++) {
                history[f][t] >>= 1;
                continuation[f][t] >>= 1;
            }
        }
    }

    void clearHistory() {
        for (int f = 0; f < BOARD_SQUARES; f++) {
            Arrays.fill(history[f], 0);
            Arrays.fill(continuation[f], 0);
        }
        resetUpdates();
    }

    void clearCounter() {
        for (int f = 0; f < BOARD_SQUARES; f++) {
            Arrays.fill(counter[f], -1);
        }
        for (int i = 0; i < counterDirtyCount; i++) {
            int idx = counterDirtyList[i];
            counterDirty[idx] = false;
            counterUpdates[idx] = -1;
        }
        counterDirtyCount = 0;
    }

    int[][] snapshotKillers() {
        int[][] snapshot = new int[killers.length][];
        for (int i = 0; i < killers.length; i++) {
            snapshot[i] = Arrays.copyOf(killers[i], killers[i].length);
        }
        return snapshot;
    }
}
