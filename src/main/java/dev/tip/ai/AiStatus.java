package dev.tip.ai;

/** Matches the {@code tip_ai_status_valid} check constraint. */
enum AiStatus {
    PENDING,
    PROCESSING,
    DONE,
    SKIPPED,
    FAILED,
    /** The model could not be reached or was resting: PLAIN once, no read, never retried. */
    UNAVAILABLE
}
