package julius.game.chessengine.board;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-local pool for {@link MoveList} instances. Each list is pre-sized once to the
 * {@link MoveList#MAX_SIZE} capacity and cleared on checkout to avoid repeated allocations in
 * hot paths such as move generation and quiescence search.
 */
public final class MoveListPool {

    private static final int MAX_POOL_SIZE = 32;
    private static final ThreadLocal<Deque<MoveList>> POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private MoveListPool() {
    }

    public static MoveList borrow() {
        Deque<MoveList> deque = POOL.get();
        MoveList list = deque.pollLast();
        if (list == null) {
            list = new MoveList();
        } else {
            list.clear();
        }
        return list;
    }

    public static void release(MoveList list) {
        if (list == null) {
            return;
        }
        list.clear();
        Deque<MoveList> deque = POOL.get();
        if (deque.size() < MAX_POOL_SIZE) {
            deque.addLast(list);
        }
    }

    static int available() {
        return POOL.get().size();
    }

    static void reset() {
        POOL.remove();
    }
}
