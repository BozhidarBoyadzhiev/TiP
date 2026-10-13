package dev.tip.ai.api;

import java.time.Instant;

/** Model calls counted in the database by the hour, for the daily summary. */
public interface AiUsage {

    /** Calls and failed calls in the hours that start at or after {@code from}. */
    Totals since(Instant from);

    record Totals(long calls, long failures) {}
}
