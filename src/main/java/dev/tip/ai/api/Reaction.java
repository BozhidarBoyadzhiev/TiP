package dev.tip.ai.api;

/**
 * How the panda reacts to a tip, see the LED patterns in {@code docs/DEVICE_PROTOCOL.md}. A flagged
 * message always reacts {@link #PLAIN}, so abuse gets nothing worth filming.
 */
public enum Reaction {
    WARM,
    PLAYFUL,
    REQUEST,
    PLAIN
}
