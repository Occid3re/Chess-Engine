package julius.game.chessengine.utils;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

/**
 * A lightweight primitive stack optimised for chess move history tracking.
 * It stores moves in a growable {@code int[]} without boxing so callers can
 * push/pop frequently without triggering {@link Integer#valueOf(int)}.
 */
public final class MoveStack {

    private static final int DEFAULT_CAPACITY = 32;

    private int[] buffer;
    private int size;

    public MoveStack() {
        this(DEFAULT_CAPACITY);
    }

    public MoveStack(int initialCapacity) {
        if (initialCapacity <= 0) {
            initialCapacity = DEFAULT_CAPACITY;
        }
        this.buffer = new int[initialCapacity];
        this.size = 0;
    }

    public MoveStack(MoveStack other) {
        this.size = other.size;
        this.buffer = Arrays.copyOf(other.buffer, other.size);
    }

    public void copyFrom(MoveStack other) {
        if (this == other) {
            return;
        }
        ensureCapacity(other.size);
        System.arraycopy(other.buffer, 0, this.buffer, 0, other.size);
        this.size = other.size;
    }

    public void push(int value) {
        ensureCapacity(size + 1);
        buffer[size++] = value;
    }

    public int pop() {
        if (size == 0) {
            throw new NoSuchElementException("MoveStack is empty");
        }
        return buffer[--size];
    }

    public int peek() {
        if (size == 0) {
            throw new NoSuchElementException("MoveStack is empty");
        }
        return buffer[size - 1];
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void clear() {
        size = 0;
    }

    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index + " outside of size " + size);
        }
        return buffer[index];
    }

    public int[] toArray() {
        return Arrays.copyOf(buffer, size);
    }

    public IntStream stream() {
        return Arrays.stream(buffer, 0, size);
    }

    private void ensureCapacity(int capacity) {
        if (capacity <= buffer.length) {
            return;
        }
        int newCapacity = Math.max(capacity, buffer.length << 1);
        buffer = Arrays.copyOf(buffer, newCapacity);
    }
}
