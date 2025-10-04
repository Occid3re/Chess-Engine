package julius.game.chessengine.ai;

final class SortBuffers {

    static final int SEE_NOT_COMPUTED = Integer.MIN_VALUE;
    static final int[] EMPTY_SEE_SCORES = new int[0];

    final int[] moveBuffer;
    final int[] scoreBuffer;
    final long[] sortKeyBuffer;
    final int[] seeBuffer;

    SortBuffers(int maxMoveListSize) {
        this.moveBuffer = new int[maxMoveListSize];
        this.scoreBuffer = new int[maxMoveListSize];
        this.sortKeyBuffer = new long[maxMoveListSize];
        this.seeBuffer = new int[maxMoveListSize];
    }

    int[] snapshotSeeScores(int size) {
        int[] snapshot = new int[size];
        System.arraycopy(seeBuffer, 0, snapshot, 0, size);
        return snapshot;
    }
}
