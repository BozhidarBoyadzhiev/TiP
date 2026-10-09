package dev.tip.tools;

import java.time.Duration;
import java.util.List;
import java.util.function.DoubleSupplier;

/** Reconnect delays from the device protocol: 1, 2, 4, 8, 16, then 30 seconds, with up to 20 percent jitter. */
final class Backoff {

    static final List<Duration> STEPS = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8),
            Duration.ofSeconds(16),
            Duration.ofSeconds(30));

    static final double MAX_JITTER = 0.2;

    private final DoubleSupplier random;
    private int attempt;

    /** {@code random} returns values in [0, 1), like {@link Math#random()}. */
    Backoff(DoubleSupplier random) {
        this.random = random;
    }

    synchronized Duration next() {
        Duration step = STEPS.get(Math.min(attempt, STEPS.size() - 1));
        attempt++;
        return Duration.ofMillis(Math.round(step.toMillis() * (1 + random.getAsDouble() * MAX_JITTER)));
    }

    synchronized void reset() {
        attempt = 0;
    }
}
