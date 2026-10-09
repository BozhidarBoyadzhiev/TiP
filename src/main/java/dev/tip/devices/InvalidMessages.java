package dev.tip.devices;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

/** Invalid inbound messages of one session within the last minute. */
final class InvalidMessages {

    static final int LIMIT = 20;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final ArrayDeque<Instant> times = new ArrayDeque<>();

    /** Records one invalid message and returns true when the session reached the limit. */
    synchronized boolean recordAndCheckLimit(Instant now) {
        Instant windowStart = now.minus(WINDOW);
        while (!times.isEmpty() && !times.peekFirst().isAfter(windowStart)) {
            times.removeFirst();
        }
        times.addLast(now);
        return times.size() >= LIMIT;
    }
}
