package dev.tip.ai;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Neutralises delimiter lookalikes in untrusted text before it goes between the tags of a prompt. A
 * lookalike of {@code <tip_message>} or {@code <owner_note>} becomes {@code [tip_message]} or {@code
 * [owner_note]} (with a slash for a closing tag): no longer a tag, so the text cannot close its own
 * block, but the model still sees that someone wrote one, which is itself a sign of an injection attempt.
 *
 * <p>Best effort, not a security boundary: the boundary is structured output and the parsers. Lookalike
 * letters from other alphabets slip past it, an accepted gap.
 */
final class Delimiters {

    /** Anything between angle brackets, including the fullwidth and small forms that NFKC folds to them. */
    private static final Pattern BRACKETED =
            Pattern.compile("[<\\x{FF1C}\\x{FE64}]([^<>\\x{FF1C}\\x{FE64}\\x{FF1E}\\x{FE65}]*)[>\\x{FF1E}\\x{FE65}]");

    /** Everything that is neither a letter nor a slash, so spaces, joiners and separators do not hide a tag. */
    private static final Pattern NOT_LETTER_OR_SLASH = Pattern.compile("[^\\p{L}/]");

    /** The tags prompts use, as their letters only, and how a lookalike of each is written instead. */
    private static final String[][] TAGS = {{"tipmessage", "tip_message"}, {"ownernote", "owner_note"}};

    private Delimiters() {}

    static String neutralise(String untrusted) {
        return BRACKETED.matcher(untrusted).replaceAll(Delimiters::neutraliseSpan);
    }

    /**
     * A bracketed span is a lookalike when its content, folded with NFKC and lowercased, starts with a tag's
     * letters once everything but letters is dropped; with a slash anywhere it reads as a closing tag. That
     * covers any case, fullwidth letters, spaces, zero width joiners and other invisible characters, extra
     * slashes, {@code tip-message}, attributes and a self closing slash. Any other span is kept as written.
     */
    private static String neutraliseSpan(MatchResult span) {
        String folded =
                Normalizer.normalize(span.group(1), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String lettersAndSlashes = NOT_LETTER_OR_SLASH.matcher(folded).replaceAll("");
        String letters = lettersAndSlashes.replace("/", "");
        for (String[] tag : TAGS) {
            if (letters.startsWith(tag[0])) {
                return lettersAndSlashes.contains("/") ? "[/" + tag[1] + "]" : "[" + tag[1] + "]";
            }
        }
        return Matcher.quoteReplacement(span.group());
    }
}
