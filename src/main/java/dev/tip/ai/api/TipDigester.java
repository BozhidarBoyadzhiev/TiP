package dev.tip.ai.api;

import java.util.List;
import java.util.UUID;

/**
 * Writes the panda's notes for an owner's day and groups the requests of the last days, with one model
 * call. Only exists while AI is enabled. Callers pass only unflagged messages and notes: nothing here can
 * tell a flagged one apart.
 */
public interface TipDigester {

    int MAX_BULLETS = 3;
    int MAX_BULLET_LENGTH = 120;
    int MAX_LABEL_LENGTH = 60;

    /**
     * @param messages the day's unflagged tip messages, as the tipper wrote them
     * @param notes the owner notes of the window's unflagged requests
     * @throws DigestFailed on a timeout, an API error or a response the parser rejects
     */
    Digest digest(List<DigestMessage> messages, List<DigestNote> notes);

    record DigestMessage(UUID tipId, String text) {}

    record DigestNote(UUID tipId, String note) {}

    /**
     * 1 to 3 bullets of at most 120 characters, and request groups whose tip ids all came from the notes
     * given, each tip in at most one group, no group empty. Every text is sanitised like a tipper's message.
     */
    record Digest(List<String> bullets, List<RequestGroup> requestGroups) {

        public Digest {
            bullets = List.copyOf(bullets);
            requestGroups = List.copyOf(requestGroups);
        }
    }

    /** A label of at most 60 characters and the tips whose notes ask for that. */
    record RequestGroup(String label, List<UUID> tipIds) {

        public RequestGroup {
            tipIds = List.copyOf(tipIds);
        }
    }

    /** {@code getMessage()} never contains a message, a note or model output, so it is safe to log. */
    final class DigestFailed extends RuntimeException {
        public DigestFailed(String reason) {
            super(reason);
        }
    }
}
