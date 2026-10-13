package dev.tip.ai.api;

/** Whether the model is being skipped for now, after a spend limit or an authentication error. */
public interface AiAvailability {

    /** True while the breaker is open: tips react PLAIN once and the notes are not rewritten. */
    boolean resting();
}
