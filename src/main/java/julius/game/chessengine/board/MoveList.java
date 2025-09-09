package julius.game.chessengine.board;

import lombok.extern.log4j.Log4j2;

import java.util.Arrays;

@Log4j2
public class MoveList {
    private int[] moves;
    private int moveCount;
    private static final int MAX_SIZE = 218; // Maximum number of legal moves

    private String stringRepresentation;
    private boolean isStringRepresentationStale = true;


    public MoveList() {
        // Pre-allocate the maximum required size to avoid costly resizes during
        // move generation. This significantly speeds up move generation by
        // eliminating array reallocation and copy overhead.
        this.moves = new int[MAX_SIZE];
        this.moveCount = 0;
    }

    // Deep copy constructor
    public MoveList(MoveList original) {
        this.moveCount = original.moveCount;
        // Ensure the cloned list also has the full capacity so that it can be
        // reused without triggering resizes.
        this.moves = new int[MAX_SIZE];
        System.arraycopy(original.moves, 0, this.moves, 0, original.moveCount);
    }

    public void add(int move) {
        // Since the array is pre-allocated to the maximum size, we can skip
        // expensive bounds checks and resizes. Rely on callers to ensure
        // the list never exceeds MAX_SIZE. The assertion will help catch
        // violations in development builds.
        //assert moveCount < MAX_SIZE : "MoveList capacity exceeded";
        moves[moveCount++] = move;
        isStringRepresentationStale = true;
    }

    public int size() {
        return moveCount;
    }

    public int getMove(int index) {
        if (index < 0 || index >= moveCount) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + moveCount);
        }
        return moves[index];
    }

    public int[] toArray() {
        return Arrays.copyOf(moves, moveCount);
    }

    public int[] get() {
        return this.moves;
    }

    public void clear() {
        moveCount = 0;
        isStringRepresentationStale = true;
    }

    /**
     * Replace the move at the given index.
     * Marks the cached string representation as stale.
     *
     * @param index index of the element to replace (0 <= index < size())
     * @param move  the new move (encoded int)
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public void setMove(int index, int move) {
        if (index < 0 || index >= moveCount) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + moveCount);
        }
        moves[index] = move;
        isStringRepresentationStale = true;
    }

    /**
     * Swap two moves in-place.
     */
    public void swap(int i, int j) {
        if (i < 0 || i >= moveCount || j < 0 || j >= moveCount) {
            throw new IndexOutOfBoundsException("i: " + i + ", j: " + j + ", Size: " + moveCount);
        }
        if (i == j) return;
        int tmp = moves[i];
        moves[i] = moves[j];
        moves[j] = tmp;
        isStringRepresentationStale = true;
    }

    @Override
    public String toString() {
        if (isStringRepresentationStale) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < size(); i++) {
                sb.append("[").append(getMove(i)).append("]:");
                sb.append(Move.convertIntToMove(getMove(i))).append(" ");
            }
            stringRepresentation = sb.toString();
            isStringRepresentationStale = false;
        }
        return stringRepresentation;
    }
}
