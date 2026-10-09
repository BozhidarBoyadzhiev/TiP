package dev.tip.ai;

import dev.tip.ai.MessageClassifier.Classification;
import dev.tip.ai.MessageClassifier.ClassificationFailed;
import java.util.Arrays;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the model's JSON in Java instead of trusting the schema it was given: a kind outside the
 * enum, a missing field, a wrong type, a reason over 200 characters, or an owner note on a kind other
 * than {@code REQUEST} or over 100 characters is an invalid response.
 */
final class ClassificationParser {

    static final int MAX_FLAG_REASON = 200;
    static final int MAX_OWNER_NOTE = 100;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

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
        return new Classification(messageKind, isFlagged, isFlagged ? reason : null, note);
    }

    /** A string or null field, with control characters removed and trimmed. Blank becomes null. */
    private static String optionalText(JsonNode node, String field) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new ClassificationFailed("invalid response: " + field + " is not a string or null");
        }
        String text = CONTROL.matcher(node.asString()).replaceAll("").strip();
        return text.isEmpty() ? null : text;
    }
}
