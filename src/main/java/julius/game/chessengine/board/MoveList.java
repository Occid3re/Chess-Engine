package julius.game.chessengine.board;

import lombok.extern.log4j.Log4j2;

import java.util.Arrays;

@Log4j2
public class MoveList {
    private int[] moves;
    private int moveCount;
    private static final int INITIAL_SIZE = 30;
    private static final int MAX_SIZE = 218; // Maximum number of legal moves

    private String stringRepresentation;
    private boolean isStringRepresentationStale = true;


    public MoveList() {
        this.moves = new int[INITIAL_SIZE];
        this.moveCount = 0;
    }

    // Deep copy constructor
    public MoveList(MoveList original) {
        this.moveCount = original.moveCount;
        this.moves = new int[original.moves.length];
        System.arraycopy(original.moves, 0, this.moves, 0, original.moveCount);
    }

    public void add(int move) {
        if (moveCount >= moves.length) {
            resizeArray();
        }
        if (moveCount < MAX_SIZE) {
            moves[moveCount] = move;
            moveCount++;
        }
        isStringRepresentationStale = true;
    }

    private void resizeArray() {
        if (moves.length >= MAX_SIZE) {
            return; // Do not resize beyond the maximum size
        }
        int newSize = Math.min(moves.length * 2, MAX_SIZE);
        int[] newArray = new int[newSize];
        System.arraycopy(moves, 0, newArray, 0, moveCount); // Copy only used elements
        moves = newArray;
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
