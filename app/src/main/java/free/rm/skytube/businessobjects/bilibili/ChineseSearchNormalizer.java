package free.rm.skytube.businessobjects.bilibili;

import android.icu.text.Transliterator;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalizes Traditional and Simplified Chinese into the same searchable form.
 */
final class ChineseSearchNormalizer {
    private static final Transliterator TO_SIMPLIFIED = createTransliterator();

    private ChineseSearchNormalizer() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        if (TO_SIMPLIFIED != null) {
            synchronized (TO_SIMPLIFIED) {
                normalized = TO_SIMPLIFIED.transliterate(normalized);
            }
        }
        return normalized.replaceAll("\\s+", " ");
    }

    static boolean matches(String query, String searchableText) {
        String normalizedQuery = normalize(query);
        String normalizedText = normalize(searchableText);
        if (normalizedQuery.isEmpty()) {
            return false;
        }
        for (String token : normalizedQuery.split(" ")) {
            if (!token.isEmpty() && !normalizedText.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static Transliterator createTransliterator() {
        try {
            return Transliterator.getInstance("Traditional-Simplified");
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
