package dev.tip.ai;

import dev.tip.ai.api.TipDigester;
import dev.tip.ai.api.TipDigester.Digest;
import dev.tip.ai.api.TipDigester.DigestFailed;
import dev.tip.ai.api.TipDigester.RequestGroup;
import dev.tip.tipping.api.MessageSanitizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the digest the model wrote, in Java. A bullet that is blank, too long, carries contact details or
 * repeats more than five words of a message in a row is dropped; at most three are kept and none left is a
 * failure. A group whose label is blank or too long is dropped, an id that names no note given is dropped,
 * a tip already in an earlier group is dropped, and a group left empty is dropped.
 */
final class DigestParser {

    /** Longer runs of a message's words count as a quote. */
    static final int MAX_QUOTED_WORDS = 5;

    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+\\.\\S+");
    private static final Pattern LINK = Pattern.compile("(?i)(https?://|www\\.)\\S+");
    private static final Pattern PHONE = Pattern.compile("\\d(?:[\\s().-]*\\d){6,}");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private DigestParser() {}

    /**
     * @param noteRefs the reference of each tip whose note was in the prompt
     * @param messages the message texts, to catch quotes
     */
    static Digest parse(JsonMapper jsonMapper, String json, Map<String, UUID> noteRefs, Collection<String> messages) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(json);
        } catch (JacksonException e) {
            throw new DigestFailed("invalid response: not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new DigestFailed("invalid response: not a JSON object");
        }
        JsonNode bullets = root.get("bullets");
        JsonNode groups = root.get("requestGroups");
        if (bullets == null || !bullets.isArray() || groups == null || !groups.isArray()) {
            throw new DigestFailed("invalid response: bullets or requestGroups is not an array");
        }
        List<String> kept = bullets(bullets, messages);
        if (kept.isEmpty()) {
            throw new DigestFailed("invalid response: no usable bullet");
        }
        return new Digest(kept, groups(groups, noteRefs));
    }

    private static List<String> bullets(JsonNode bullets, Collection<String> messages) {
        Set<String> quotable = new HashSet<>();
        messages.forEach(message -> quotable.addAll(runs(message)));
        List<String> kept = new ArrayList<>();
        for (JsonNode node : bullets) {
            String bullet = node.isString() ? MessageSanitizer.sanitize(node.asString()) : null;
            if (bullet == null
                    || bullet.length() > TipDigester.MAX_BULLET_LENGTH
                    || containsContactDetails(bullet)
                    || runs(bullet).stream().anyMatch(quotable::contains)) {
                continue;
            }
            kept.add(bullet);
            if (kept.size() == TipDigester.MAX_BULLETS) {
                break;
            }
        }
        return kept;
    }

    private static List<RequestGroup> groups(JsonNode groups, Map<String, UUID> noteRefs) {
        Set<UUID> grouped = new HashSet<>();
        List<RequestGroup> kept = new ArrayList<>();
        for (JsonNode group : groups) {
            JsonNode labelNode = group.get("label");
            JsonNode ids = group.get("ids");
            String label =
                    labelNode != null && labelNode.isString() ? MessageSanitizer.sanitize(labelNode.asString()) : null;
            if (label == null || label.length() > TipDigester.MAX_LABEL_LENGTH || ids == null || !ids.isArray()) {
                continue;
            }
            List<UUID> tipIds = new ArrayList<>();
            for (JsonNode id : ids) {
                UUID tipId = id.isString() ? noteRefs.get(id.asString()) : null;
                if (tipId != null && grouped.add(tipId)) {
                    tipIds.add(tipId);
                }
            }
            if (!tipIds.isEmpty()) {
                kept.add(new RequestGroup(label, tipIds));
            }
        }
        return kept;
    }

    private static boolean containsContactDetails(String text) {
        return EMAIL.matcher(text).find()
                || LINK.matcher(text).find()
                || PHONE.matcher(text).find();
    }

    /** Every run of {@code MAX_QUOTED_WORDS + 1} consecutive words, lowercased, punctuation ignored. */
    private static List<String> runs(String text) {
        List<String> words = Arrays.stream(NOT_WORD.split(text.toLowerCase(Locale.ROOT)))
                .filter(word -> !word.isEmpty())
                .toList();
        List<String> runs = new ArrayList<>();
        for (int i = 0; i + MAX_QUOTED_WORDS < words.size(); i++) {
            runs.add(String.join(" ", words.subList(i, i + MAX_QUOTED_WORDS + 1)));
        }
        return runs;
    }
}
