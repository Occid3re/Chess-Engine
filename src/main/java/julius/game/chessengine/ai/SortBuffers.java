package julius.game.chessengine.ai;

final class SortBuffers {

    final int[] moveBuffer;
    final int[] scoreBuffer;
    final long[] sortKeyBuffer;

    SortBuffers(int maxMoveListSize) {
        this.moveBuffer = new int[maxMoveListSize];
        this.scoreBuffer = new int[maxMoveListSize];
        this.sortKeyBuffer = new long[maxMoveListSize];
    }
}
