package julius.game.chessengine.ai;

import julius.game.chessengine.board.MoveHelper;

import java.util.Arrays;

/**
 * Thread-local heuristic state used during move ordering: killer moves, history
 * table, and counter-move table. Instances are initialised from a snapshot at the
 * beginning of each iterative-deepening iteration, updated locally during the
 * search, and merged back into the shared global state afterwards.
 */
final class Heuristics {

    static final int BOARD_SQUARES = 64;
    static final int HISTORY_SIZE = BOARD_SQUARES * BOARD_SQUARES;
    static final int NUM_KILLER_MOVES = 2;

    private int[][] killers;
    private final int[][] history;
    private final int[][] counter;

    private boolean[] killerDirty;
    private int[] killerDirtyList;
    private int killerDirtyCount;

    private final int[] historyDelta;
    private final boolean[] historyDirty;
    private final int[] historyDirtyList;
    private int historyDirtyCount;

    private final int[] counterUpdates;
    private final boolean[] counterDirty;
    private final int[] counterDirtyList;
    private int counterDirtyCount;

    private long preparedTaskId = Long.MIN_VALUE;
    private int preparedDepth = -1;

    Heuristics(int depth) {
        this.killers = allocateKillers(Math.max(1, depth));
        this.history = new int[BOARD_SQUARES][BOARD_SQUARES];
        this.counter = new int[BOARD_SQUARES][BOARD_SQUARES];
        for (int f = 0; f < BOARD_SQUARES; f++) {
            Arrays.fill(counter[f], -1);
        }
        this.killerDirty = new boolean[Math.max(1, depth)];
        this.killerDirtyList = new int[Math.max(1, depth)];
        this.historyDelta = new int[HISTORY_SIZE];
        this.historyDirty = new boolean[HISTORY_SIZE];
        this.historyDirtyList = new int[HISTORY_SIZE];
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

    void beginIteration(Snapshot snapshot, int requiredDepth) {
        resetUpdates();
        ensureCapacity(requiredDepth);
        int limit = Math.min(requiredDepth, snapshot.killers.length);
        for (int d = 0; d < limit; d++) {
            System.arraycopy(snapshot.killers[d], 0, killers[d], 0, NUM_KILLER_MOVES);
        }
        for (int d = limit; d < requiredDepth; d++) {
            Arrays.fill(killers[d], -1);
        }
        for (int f = 0; f < BOARD_SQUARES; f++) {
            System.arraycopy(snapshot.history[f], 0, history[f], 0, BOARD_SQUARES);
            System.arraycopy(snapshot.counter[f], 0, counter[f], 0, BOARD_SQUARES);
        }
    }

    Snapshot snapshot(int requiredDepth) {
        int killerDepth = Math.min(requiredDepth, killers.length);
        int[][] killerCopy = new int[killerDepth][];
        for (int d = 0; d < killerDepth; d++) {
            killerCopy[d] = Arrays.copyOf(killers[d], NUM_KILLER_MOVES);
        }
        int[][] historyCopy = new int[BOARD_SQUARES][];
        int[][] counterCopy = new int[BOARD_SQUARES][];
        for (int f = 0; f < BOARD_SQUARES; f++) {
            historyCopy[f] = Arrays.copyOf(history[f], BOARD_SQUARES);
            counterCopy[f] = Arrays.copyOf(counter[f], BOARD_SQUARES);
        }
        return new Snapshot(killerCopy, historyCopy, counterCopy);
    }

    record Snapshot(int[][] killers, int[][] history, int[][] counter) {
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
        return killerDirtyCount > 0 || historyDirtyCount > 0 || counterDirtyCount > 0;
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

    void addHistory(int move, int delta) {
        if (move == -1 || MoveHelper.isCapture(move) || delta <= 0) {
            return;
        }
        int from = move & 0x3F;
        int to = (move >>> 6) & 0x3F;
        history[from][to] += delta;
        int idx = (from << 6) | to;
        if (!historyDirty[idx]) {
            historyDirty[idx] = true;
            historyDirtyList[historyDirtyCount++] = idx;
        }
        historyDelta[idx] += delta;
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

    boolean hasPendingUpdates() {
        return killerDirtyCount > 0 || historyDirtyCount > 0 || counterDirtyCount > 0;
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

    void decayHistory(int divisor) {
        if (divisor <= 1) {
            return;
        }
        for (int f = 0; f < BOARD_SQUARES; f++) {
            for (int t = 0; t < BOARD_SQUARES; t++) {
                history[f][t] /= divisor;
            }
        }
    }

    void clearHistory() {
        for (int f = 0; f < BOARD_SQUARES; f++) {
            Arrays.fill(history[f], 0);
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

    int[][] getKillers() {
        return killers;
    }

    int[][] getHistory() {
        return history;
    }

    int[][] getCounter() {
        return counter;
    }
}
