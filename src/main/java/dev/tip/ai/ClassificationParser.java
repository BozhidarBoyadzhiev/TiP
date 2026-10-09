package dev.tip.ai;

import dev.tip.ai.MessageClassifier.Classification;
import dev.tip.ai.MessageClassifier.ClassificationFailed;
import dev.tip.ai.api.Reaction;
import java.util.Arrays;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the model's JSON in Java instead of trusting the schema it was given: a reaction outside
 * the enum, a missing field, a wrong type or a reason over 200 characters is an invalid response.
 */
final class ClassificationParser {

    static final int MAX_FLAG_REASON = 200;

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
        JsonNode reaction = root.get("reaction");
        JsonNode flagged = root.get("flagged");
        JsonNode flagReason = root.get("flagReason");
        if (reaction == null || flagged == null || flagReason == null) {
            throw new ClassificationFailed("invalid response: missing field");
        }
        if (!reaction.isString()
                || Arrays.stream(Reaction.values()).noneMatch(r -> r.name().equals(reaction.asString()))) {
            throw new ClassificationFailed("invalid response: reaction not allowed");
        }
        if (!flagged.isBoolean()) {
            throw new ClassificationFailed("invalid response: flagged is not a boolean");
        }
        if (!flagReason.isNull() && !flagReason.isString()) {
            throw new ClassificationFailed("invalid response: flagReason is not a string or null");
        }
        String reason = flagReason.isNull() || flagReason.asString().isBlank()
                ? null
                : flagReason.asString().strip();
        if (reason != null && reason.length() > MAX_FLAG_REASON) {
            throw new ClassificationFailed("invalid response: flagReason too long");
        }
        boolean isFlagged = flagged.booleanValue();
        return new Classification(Reaction.valueOf(reaction.asString()), isFlagged, isFlagged ? reason : null);
    }
}
