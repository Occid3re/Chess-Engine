package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;

final class SortBuffers {

    final int[] moveBuffer;
    final int[] scoreBuffer;
    final long[] sortKeyBuffer;
    final int[] seeScoreBuffer;
    final boolean[] seeComputedBuffer;
    final int[] orderedMoveBuffer;
    final int[] orderedSeeScoreBuffer;
    final boolean[] orderedSeeComputedBuffer;
    final MoveOrderingResult orderingResult;

    SortBuffers(int maxMoveListSize) {
        this.moveBuffer = new int[maxMoveListSize];
        this.scoreBuffer = new int[maxMoveListSize];
        this.sortKeyBuffer = new long[maxMoveListSize];
        this.seeScoreBuffer = new int[maxMoveListSize];
        this.seeComputedBuffer = new boolean[maxMoveListSize];
        this.orderedMoveBuffer = new int[maxMoveListSize];
        this.orderedSeeScoreBuffer = new int[maxMoveListSize];
        this.orderedSeeComputedBuffer = new boolean[maxMoveListSize];
        this.orderingResult = new MoveOrderingResult();
    }

    static final class MoveOrderingResult {
        private IntArrayList moves;
        private int size;
        private int[] seeScores;
        private boolean[] seeComputed;

        MoveOrderingResult reset(IntArrayList moves, int size,
                                 int[] seeScores, boolean[] seeComputed) {
            this.moves = moves;
            this.size = size;
            this.seeScores = seeScores;
            this.seeComputed = seeComputed;
            return this;
        }

        IntArrayList moves() {
            return moves;
        }

        int size() {
            return size;
        }

        boolean hasSeeAt(int index) {
            return index < size && seeComputed[index];
        }

        int seeAt(int index) {
            return seeScores[index];
        }

        void storeSeeAt(int index, int value) {
            if (index >= size) {
                return;
            }
            seeScores[index] = value;
            seeComputed[index] = true;
        }
    }
}
