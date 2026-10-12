package dev.tip.dashboard;

/**
 * The one definition of which messages the owner does not see, as SQL on the {@code tip} table under the
 * alias {@code t}. The AI's flag ({@code t.flagged}) only hides a message by default: the owner can read it
 * on demand and overrule the flag, which sets {@code t.owner_verdict}. Every owner facing query (the tip
 * list and its filters, today, requests, the digest's input, the hidden panel and every count) decides
 * through these fragments, never by reading the flag itself; {@code HiddenMessagesRuleTest} checks that.
 */
final class HiddenMessages {

    /** The AI flagged the message, whatever the owner decided since. Reveal and verdict act only on these. */
    static final String AI_FLAGGED = "t.flagged";

    /** The owner overruled the AI's flag. */
    static final String UNHIDDEN = "(t.flagged AND t.owner_verdict IS NOT NULL)";

    /** Hidden from the owner: flagged by the AI and not overruled. */
    static final String HIDDEN = "(t.flagged AND t.owner_verdict IS NULL)";

    /** Not hidden: never flagged, or overruled by the owner. */
    static final String SHOWN = "NOT " + HIDDEN;

    private HiddenMessages() {}

    /** {@code column} while the message is shown, null while it is hidden. */
    static String unlessHidden(String column) {
        return "CASE WHEN " + HIDDEN + " THEN NULL ELSE " + column + " END";
    }
}
