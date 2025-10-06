package julius.game.chessengine.utils;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class MoveStackTest {

    @Test
    void shouldPushAndPopInLifoOrder() {
        MoveStack stack = new MoveStack();
        stack.push(1);
        stack.push(2);
        stack.push(3);

        assertEquals(3, stack.size());
        assertEquals(3, stack.peek());
        assertEquals(3, stack.pop());
        assertEquals(2, stack.pop());
        assertEquals(1, stack.pop());
        assertTrue(stack.isEmpty());
    }

    @Test
    void shouldGrowBeyondInitialCapacity() {
        MoveStack stack = new MoveStack(1);
        for (int i = 0; i < 10; i++) {
            stack.push(i);
        }
        assertEquals(10, stack.size());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, stack.toArray());
    }

    @Test
    void shouldCopyFromAnotherStack() {
        MoveStack source = new MoveStack();
        source.push(5);
        source.push(6);

        MoveStack target = new MoveStack();
        target.push(1);
        target.push(2);
        target.copyFrom(source);

        assertEquals(2, target.size());
        assertArrayEquals(source.toArray(), target.toArray());

        source.push(7);
        assertNotEquals(source.size(), target.size(), "Copy should be independent after modification");
    }

    @Test
    void shouldThrowWhenPoppingEmptyStack() {
        MoveStack stack = new MoveStack();
        assertThrows(NoSuchElementException.class, stack::pop);
        assertThrows(NoSuchElementException.class, stack::peek);
    }
}
