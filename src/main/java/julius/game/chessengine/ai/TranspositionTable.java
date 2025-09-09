package julius.game.chessengine.ai;

/**
 * Minimal abstraction for a transposition table used by the search.
 * Implementations may or may not be thread-safe.
 */
public interface TranspositionTable<V> {
    V get(long key);
    void put(long key, V value);
    void clear();
    int size();
}
