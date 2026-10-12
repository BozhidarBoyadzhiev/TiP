package dev.tip.dashboard;

import java.util.Locale;

/** English names of the ISO 639-1 codes the AI stores. */
final class Languages {

    private Languages() {}

    /** "French" for {@code fr}. Null for null, and for a code Java does not know as a language. */
    static String englishName(String code) {
        if (code == null) {
            return null;
        }
        String name = Locale.of(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() || name.equals(code) ? null : name;
    }
}
