package dev.tip.devices;

import java.util.UUID;
import java.util.regex.Pattern;

/** Strict uuid parsing. {@link UUID#fromString} also accepts forms like {@code 1-1-1-1-1}. */
final class Uuids {

    private static final Pattern CANONICAL =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private Uuids() {}

    /** The uuid when the text is its canonical lowercase form, otherwise null. */
    static UUID parse(String text) {
        return text != null && CANONICAL.matcher(text).matches() ? UUID.fromString(text) : null;
    }
}
