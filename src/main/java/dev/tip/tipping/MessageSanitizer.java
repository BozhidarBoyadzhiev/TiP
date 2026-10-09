package dev.tip.tipping;

import java.util.regex.Pattern;

/** Cleans a tipper's message before it is stored. */
final class MessageSanitizer {

    private static final Pattern LINE_BREAKS_AND_TABS = Pattern.compile("[\\t\\n\\r\\u000B\\f\\u0085\\u2028\\u2029]+");
    private static final Pattern CONTROL_AND_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\u200D]]");

    private MessageSanitizer() {}

    /**
     * Turns line breaks and tabs into single spaces, removes other control and invisible format
     * characters (including bidirectional overrides, but keeping the zero width joiner that emoji
     * sequences need), and trims. Returns null when nothing is left.
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
