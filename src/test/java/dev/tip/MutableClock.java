package dev.tip;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The application clock in tests. Follows the system clock until a test pins it, so tests drive time
 * directly instead of sleeping. {@link IntegrationTest} releases it before every test.
 */
public class MutableClock extends Clock {

    private static final Clock SYSTEM = Clock.systemUTC();

    private volatile Instant pinned;

    public void pin(Instant instant) {
        pinned = instant;
    }

    /** Moves a pinned clock forward. */
    public void advance(Duration duration) {
        if (pinned == null) {
            throw new IllegalStateException("Pin the clock before advancing it");
        }
        pinned = pinned.plus(duration);
    }

    public void release() {
        pinned = null;
    }

    @Override
    public Instant instant() {
        Instant now = pinned;
        return now != null ? now : SYSTEM.instant();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The application clock is always UTC");
    }
}
