package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;

final class SortBuffers {

    final int[] moveBuffer;

    final IntArrayList ttMoves;
    final IntArrayList ttScores;
    final IntArrayList promotionMoves;
    final IntArrayList promotionScores;
    final IntArrayList goodCaptureMoves;
    final IntArrayList goodCaptureScores;
    final IntArrayList equalCaptureMoves;
    final IntArrayList equalCaptureScores;
    final IntArrayList killer0Moves;
    final IntArrayList killer0Scores;
    final IntArrayList killer1Moves;
    final IntArrayList killer1Scores;
    final IntArrayList quietMoves;
    final IntArrayList quietScores;
    final IntArrayList badCaptureMoves;
    final IntArrayList badCaptureScores;

    SortBuffers(int maxMoveListSize) {
        this.moveBuffer = new int[maxMoveListSize];

        // Buckets are intentionally sized to the maximum move list so we can reuse the
        // same arrays without triggering re-allocation across iterations.
        this.ttMoves = new IntArrayList(1);
        this.ttScores = new IntArrayList(1);
        this.promotionMoves = new IntArrayList(maxMoveListSize);
        this.promotionScores = new IntArrayList(maxMoveListSize);
        this.goodCaptureMoves = new IntArrayList(maxMoveListSize);
        this.goodCaptureScores = new IntArrayList(maxMoveListSize);
        this.equalCaptureMoves = new IntArrayList(maxMoveListSize);
        this.equalCaptureScores = new IntArrayList(maxMoveListSize);
        this.killer0Moves = new IntArrayList(maxMoveListSize);
        this.killer0Scores = new IntArrayList(maxMoveListSize);
        this.killer1Moves = new IntArrayList(maxMoveListSize);
        this.killer1Scores = new IntArrayList(maxMoveListSize);
        this.quietMoves = new IntArrayList(maxMoveListSize);
        this.quietScores = new IntArrayList(maxMoveListSize);
        this.badCaptureMoves = new IntArrayList(maxMoveListSize);
        this.badCaptureScores = new IntArrayList(maxMoveListSize);
    }

    void clearBuckets() {
        ttMoves.clear();
        ttScores.clear();
        promotionMoves.clear();
        promotionScores.clear();
        goodCaptureMoves.clear();
        goodCaptureScores.clear();
        equalCaptureMoves.clear();
        equalCaptureScores.clear();
        killer0Moves.clear();
        killer0Scores.clear();
        killer1Moves.clear();
        killer1Scores.clear();
        quietMoves.clear();
        quietScores.clear();
        badCaptureMoves.clear();
        badCaptureScores.clear();
    }
}
