package dev.tip.tipping;

import java.util.regex.Pattern;

/** Cleans a tipper's message before it is stored. */
final class MessageSanitizer {

    private static final Pattern LINE_BREAKS_AND_TABS = Pattern.compile("[\\t\\n\\r\\u000B\\f\\u0085\\u2028\\u2029]+");
    private static final Pattern CONTROL_AND_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Cs}&&[^\\u200D]]");

    private MessageSanitizer() {}

    /**
     * Turns line breaks and tabs into single spaces, removes other control and invisible format
     * characters (including bidirectional overrides, but keeping the zero width joiner that emoji
     * sequences need), removes lone surrogates, and trims. Returns null when nothing is left.
     *
     * <p>A lone surrogate is half of an emoji, which a JSON escape can carry on its own. It
     * is not a character, so the database driver would store it as {@code ?}. Java regex matches code
     * points, so {@code \p{Cs}} matches only an unpaired half and a complete emoji is kept. Nothing here
     * truncates, so no step splits a pair.
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = LINE_BREAKS_AND_TABS.matcher(raw).replaceAll(" ");
        cleaned = CONTROL_AND_FORMAT.matcher(cleaned).replaceAll("").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
