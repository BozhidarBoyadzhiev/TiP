package dev.tip.ai;

import dev.tip.ai.api.Reaction;

/** Reads a tip message and decides the reaction and whether it needs moderation. */
interface MessageClassifier {

    /**
     * @throws ClassificationFailed on a timeout, an API error or a response outside the schema
     */
    Classification classify(String message);

    /** {@code flagReason} is at most 200 characters and only set when flagged. */
    record Classification(Reaction reaction, boolean flagged, String flagReason) {}

    /** {@code getMessage()} never contains the tip message or model output, so it is safe to log. */
    final class ClassificationFailed extends RuntimeException {
        ClassificationFailed(String reason) {
            super(reason);
        }
    }
}
