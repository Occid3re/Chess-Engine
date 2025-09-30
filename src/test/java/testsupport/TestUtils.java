package testsupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shared reflection helpers and domain-specific utilities to keep tests concise.
 */
public final class TestUtils {

    private static final Logger log = LogManager.getLogger(TestUtils.class);

    private TestUtils() {
    }

    public static Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = resolveField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    public static void writeField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = resolveField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
        log.debug("Field {} on {} updated to {}", fieldName, target.getClass().getSimpleName(), value);
    }

    public static int readKillersLength(Object heuristics) throws ReflectiveOperationException {
        Field killersField = heuristics.getClass().getDeclaredField("killers");
        killersField.setAccessible(true);
        int[][] killers = (int[][]) killersField.get(heuristics);
        return killers.length;
    }

    @SuppressWarnings("unchecked")
    public static void putDummyEntry(Object ai, String field, long key, Object value) throws ReflectiveOperationException {
        Object table = readField(ai, field);
        if (table == null) {
            throw new IllegalStateException(field + " should not be null");
        }
        Method put = table.getClass().getMethod("put", long.class, Object.class, int.class);
        put.invoke(table, key, value, 1);
    }

    public static int extractTableSize(Object ai, String field) throws ReflectiveOperationException {
        Object table = readField(ai, field);
        Method size = table.getClass().getMethod("size");
        return (int) size.invoke(table);
    }

    private static Field resolveField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
