package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;

final class MoveContainerUtils {
    private MoveContainerUtils() {
    }

    static boolean contains(IntArrayList moves, int target) {
        for (int i = 0; i < moves.size(); i++) {
            if (moves.getInt(i) == target) {
                return true;
            }
        }
        return false;
    }

    static void rotateLeft(IntArrayList moves, int distance) {
        final int size = moves.size();
        if (size <= 1) {
            return;
        }

        int shift = distance % size;
        if (shift < 0) {
            shift += size;
        }
        if (shift == 0) {
            return;
        }

        int[] elements = moves.elements();
        int gcd = gcd(shift, size);
        for (int start = 0; start < gcd; start++) {
            int temp = elements[start];
            int idx = start;
            while (true) {
                int next = idx + shift;
                if (next >= size) {
                    next -= size;
                }
                if (next == start) {
                    break;
                }
                elements[idx] = elements[next];
                idx = next;
            }
            elements[idx] = temp;
        }
    }

    static void overwriteFromBuffer(IntArrayList target, int[] buffer, int length) {
        if (length > target.size()) {
            throw new IllegalArgumentException("Buffer length exceeds move list size");
        }
        int[] elements = target.elements();
        System.arraycopy(buffer, 0, elements, 0, length);
    }

    static IntArrayList filterCapturesAndPromotions(IntArrayList moves) {
        IntArrayList filtered = new IntArrayList(moves.size());
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (MoveHelper.isCapture(move) || MoveHelper.isPawnPromotionMove(move)) {
                filtered.add(move);
            }
        }
        return filtered;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
