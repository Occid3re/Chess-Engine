package julius.game.chessengine.ai;

import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.TestLoggingExtension;

import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
@ExtendWith(TestLoggingExtension.class)
class AITest_UtilitiesAndMath {

    @Test
    @DisplayName("roundUpToPowerOfTwo handles edge cases and saturates")
    void roundUpToPowerOfTwoEdgeCases() throws Exception {
        Method method = AI.class.getDeclaredMethod("roundUpToPowerOfTwo", int.class);
        method.setAccessible(true);

        int[] inputs = {0, 1, 2, 3, 5, 1023, 1024, 1025, Integer.MAX_VALUE};
        for (int value : inputs) {
            int rounded = (int) method.invoke(null, value);
            log.info("roundUpToPowerOfTwo({}) -> {}", value, rounded);
            assertTrue(rounded >= 1, "Result must be at least one");
            assertTrue(isPowerOfTwo(rounded), "Result must be a power of two");
            if (value > (1 << 30)) {
                assertEquals(1 << 30, rounded, "Values above 2^30 should saturate");
            } else if (Integer.highestOneBit(value) == value && value > 0) {
                assertEquals(value, rounded, "Exact powers must remain unchanged");
            } else if (value > 1 && value <= (1 << 30)) {
                assertTrue(rounded >= value, "Rounded value must not shrink");
            }
        }
    }

    @Test
    @DisplayName("computeTableCapacity clamps to range and returns power of two")
    void computeTableCapacityProperties() throws Exception {
        Method method = AI.class.getDeclaredMethod("computeTableCapacity", long.class, int.class, int.class, int.class);
        method.setAccessible(true);

        Random random = new Random(42);
        int min = 1 << 12;
        int max = 1 << 20;
        for (int i = 0; i < 50; i++) {
            long budget = 1 + Math.abs(random.nextLong()) % (1L << 34);
            int entrySize = 16 + random.nextInt(32);
            int capacity = (int) method.invoke(null, budget, entrySize, min, max);
            log.info("Capacity computed from budget={} bytes entrySize={} -> {}", budget, entrySize, capacity);
            assertTrue(capacity >= min && capacity <= max, "Capacity must be clamped within bounds");
            assertTrue(isPowerOfTwo(capacity), "Capacity must be a power of two");
        }

        int belowMin = (int) method.invoke(null, 1L, 1024, min, max);
        assertEquals(min, belowMin, "Tiny budgets should clamp to minimum");

        int aboveMax = (int) method.invoke(null, Long.MAX_VALUE, 1, min, max);
        assertEquals(max, aboveMax, "Huge budgets should clamp to maximum");
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}

