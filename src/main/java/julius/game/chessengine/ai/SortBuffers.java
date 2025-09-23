package julius.game.chessengine.ai;

import julius.game.chessengine.board.MoveList;

final class SortBuffers {

    final int[] moveBuffer;
    final int[] scoreBuffer;
    final long[] sortKeyBuffer;
    final MoveList captureFilterMoves;

    SortBuffers(int maxMoveListSize) {
        this.moveBuffer = new int[maxMoveListSize];
        this.scoreBuffer = new int[maxMoveListSize];
        this.sortKeyBuffer = new long[maxMoveListSize];
        this.captureFilterMoves = new MoveList();
    }
}
