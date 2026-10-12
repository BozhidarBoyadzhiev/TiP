package dev.tip.ai;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The key that counts two spellings of a requested song as one: lowercase, accents removed, punctuation
 * removed, whitespace collapsed and a leading "the" dropped. "The Wonderwall!" and "wonderwall" share the
 * key {@code wonderwall}; "Wonder Wall" does not.
 */
final class SongKey {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String LEADING_THE = "the ";

    /** The column's size. NFKD can lengthen a few rare characters, such as a ligature, past the song's cap. */
    static final int MAX_LENGTH = 80;

    private SongKey() {}

    /** Null when the song is null, nothing is left of it, or the key would not fit its column. */
    static String of(String song) {
        if (song == null) {
            return null;
        }
        String key = Normalizer.normalize(song, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        key = MARKS.matcher(key).replaceAll("");
        key = PUNCTUATION.matcher(key).replaceAll("");
        key = WHITESPACE.matcher(key).replaceAll(" ").strip();
        if (key.startsWith(LEADING_THE) && key.length() > LEADING_THE.length()) {
            key = key.substring(LEADING_THE.length());
        }
        return key.isEmpty() || key.length() > MAX_LENGTH ? null : key;
    }
}
