package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;

final class SortBuffers {

    final int[] moveBuffer;
    final int[] scoreBuffer;
    final int[] orderedBuffer;
    final IntArrayList[] bucketIndexes;

    SortBuffers(int maxMoveListSize, int bucketCount) {
        this.moveBuffer = new int[maxMoveListSize];
        this.scoreBuffer = new int[maxMoveListSize];
        this.orderedBuffer = new int[maxMoveListSize];
        this.bucketIndexes = new IntArrayList[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            bucketIndexes[i] = new IntArrayList(maxMoveListSize);
        }
    }
}
