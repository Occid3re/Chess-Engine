package julius.game.chessengine.tuning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class ParameterNormalizer {

    private ParameterNormalizer() {
    }

    static String normalizeKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Key must not be null");
        }
        int length = key.length();
        int start = 0;
        int end = length;
        while (start < length && Character.isWhitespace(key.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(key.charAt(end - 1))) {
            end--;
        }
        if (start == end) {
            throw new IllegalArgumentException("Key must not be blank");
        }
        boolean needsLowerCase = false;
        for (int i = start; i < end; i++) {
            char c = key.charAt(i);
            if (Character.toLowerCase(c) != c) {
                needsLowerCase = true;
                break;
            }
        }
        if (start == 0 && end == length && !needsLowerCase) {
            return key;
        }
        String trimmed = (start == 0 && end == length) ? key : key.substring(start, end);
        return needsLowerCase ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    static Map<String, Double> normalize(Map<String, Double> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isNaN()) {
                return;
            }
            normalized.put(normalizeKey(key), value);
        });
        return Collections.unmodifiableMap(normalized);
    }
}
