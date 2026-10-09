package dev.tip.ai;

import dev.tip.ai.api.Reaction;

/** What kind of message the model read. Stored as {@code message_kind}, even when the message is flagged. */
enum MessageKind {
    WARM,
    PLAYFUL,
    REQUEST,
    PLAIN;

    /** The reaction this kind plays when the message is not flagged. */
    Reaction reaction() {
        return Reaction.valueOf(name());
    }
}
