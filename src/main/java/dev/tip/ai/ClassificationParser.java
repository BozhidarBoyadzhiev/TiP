package dev.tip.ai;

import dev.tip.ai.MessageClassifier.Classification;
import dev.tip.ai.MessageClassifier.ClassificationFailed;
import dev.tip.tipping.api.MessageSanitizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the model's JSON in Java instead of trusting the schema it was given: a kind outside the
 * enum, a missing field, a wrong type, a reason over 200 characters, or an owner note on a kind other
 * than {@code REQUEST} or over 100 characters is an invalid response.
 *
 * <p>The language, the English text and the song are extras: one that breaks its rule is dropped, never
 * the whole classification, so the kind and the flag never depend on them. Every text the model wrote goes
 * through {@link MessageSanitizer}, like a tipper's message.
 */
final class ClassificationParser {

    static final int MAX_FLAG_REASON = 200;
    static final int MAX_OWNER_NOTE = 100;
    static final int MAX_ENGLISH_TEXT = 280;
    static final int MAX_SONG = 80;

    static final String ENGLISH = "en";

    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2}");

    private ClassificationParser() {}

    static Classification parse(JsonMapper jsonMapper, String json) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(json);
        } catch (JacksonException e) {
            throw new ClassificationFailed("invalid response: not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ClassificationFailed("invalid response: not a JSON object");
        }
        JsonNode kind = root.get("kind");
        JsonNode flagged = root.get("flagged");
        JsonNode flagReason = root.get("flagReason");
        JsonNode ownerNote = root.get("ownerNote");
        if (kind == null || flagged == null || flagReason == null || ownerNote == null) {
            throw new ClassificationFailed("invalid response: missing field");
        }
        if (!kind.isString()
                || Arrays.stream(MessageKind.values()).noneMatch(k -> k.name().equals(kind.asString()))) {
            throw new ClassificationFailed("invalid response: kind not allowed");
        }
        if (!flagged.isBoolean()) {
            throw new ClassificationFailed("invalid response: flagged is not a boolean");
        }
        String reason = optionalText(flagReason, "flagReason");
        if (reason != null && reason.length() > MAX_FLAG_REASON) {
            throw new ClassificationFailed("invalid response: flagReason too long");
        }
        MessageKind messageKind = MessageKind.valueOf(kind.asString());
        String note = optionalText(ownerNote, "ownerNote");
        if (note != null && messageKind != MessageKind.REQUEST) {
            throw new ClassificationFailed("invalid response: ownerNote on a kind other than REQUEST");
        }
        if (note != null && note.length() > MAX_OWNER_NOTE) {
            throw new ClassificationFailed("invalid response: ownerNote too long");
        }
        boolean isFlagged = flagged.booleanValue();
        String language = language(root.get("language"));
        String englishText = language == null || language.equals(ENGLISH) || isFlagged
                ? null
                : extraText(root.get("englishText"), MAX_ENGLISH_TEXT);
        String song = messageKind == MessageKind.REQUEST && !isFlagged ? extraText(root.get("song"), MAX_SONG) : null;
        return new Classification(messageKind, isFlagged, isFlagged ? reason : null, note, language, englishText, song);
    }

    /**
     * A lowercase two letter code that Java knows as a language, so it has an English display name other
     * than the code itself. Anything else, such as {@code xx}, {@code EN} or {@code eng}, counts as absent.
     */
    static String language(JsonNode node) {
        if (node == null
                || !node.isString()
                || !LANGUAGE_CODE.matcher(node.asString()).matches()) {
            return null;
        }
        String code = node.asString();
        String name = Locale.of(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() || name.equals(code) ? null : code;
    }

    /** An optional extra: sanitised, and dropped when it is missing, not a string, blank or too long. */
    private static String extraText(JsonNode node, int maxLength) {
        if (node == null || !node.isString()) {
            return null;
        }
        String text = MessageSanitizer.sanitize(node.asString());
        return text == null || text.length() > maxLength ? null : text;
    }

    /** A string or null field, sanitised like a tipper's message. Blank becomes null. */
    private static String optionalText(JsonNode node, String field) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new ClassificationFailed("invalid response: " + field + " is not a string or null");
        }
        return MessageSanitizer.sanitize(node.asString());
    }
}
