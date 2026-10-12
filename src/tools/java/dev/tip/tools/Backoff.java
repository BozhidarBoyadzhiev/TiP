package dev.tip.tools;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Reconnect delays from the client policy in {@code docs/DEVICE_PROTOCOL.md}: 1 second doubling to a cap
 * of 60, each shortened by up to 20 percent at random, starting over only after a connection stayed up 60
 * seconds.
 */
final class Backoff {

    static final Duration FIRST = Duration.ofSeconds(1);
    static final Duration CAP = Duration.ofSeconds(60);
    static final Duration STABLE = Duration.ofSeconds(60);
    static final double MAX_JITTER = 0.2;

    /** Past this many doublings the cap has long been reached; the bound keeps the shift safe. */
    private static final int MAX_DOUBLINGS = 16;

    private final DoubleSupplier random;
    private int attempt;

    /** {@code random} returns values in [0, 1), like {@link Math#random()}. */
    Backoff(DoubleSupplier random) {
        this.random = random;
    }

    /**
     * The delay before the next attempt. {@code connectedFor} is how long the connection that just ended was
     * up, zero for an attempt that never connected.
     */
    synchronized Duration next(Duration connectedFor) {
        if (connectedFor.compareTo(STABLE) >= 0) {
            attempt = 0;
        }
        long step = Math.min(FIRST.toMillis() << Math.min(attempt, MAX_DOUBLINGS), CAP.toMillis());
        attempt++;
        return Duration.ofMillis(Math.round(step * (1 - random.getAsDouble() * MAX_JITTER)));
    }
}
