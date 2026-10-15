package dev.tip.ai;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A global cap on classification calls, per UTC hour and per UTC day ({@link ClassificationCapProperties}). PayPal
 * sandbox payments cost nothing, so a paid tip alone does not bound what anyone can make the model read. Past the
 * cap a tip is not classified, now or later: it reacts PLAIN once as UNAVAILABLE. One WARN per window that ran out.
 * In memory, like the rest of the single instance backend; a restart starts the counts again.
 */
@Component
class ClassificationCap {

    private static final Logger log = LoggerFactory.getLogger(ClassificationCap.class);

    private final ClassificationCapProperties limits;
    private final Clock clock;

    private final Window hour = new Window(ChronoUnit.HOURS, "hour");
    private final Window day = new Window(ChronoUnit.DAYS, "day");

    ClassificationCap(ClassificationCapProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /** Takes one call from the hour's and the day's allowance. False, taking nothing, when either has run out. */
    synchronized boolean tryTake() {
        Instant now = clock.instant();
        hour.roll(now);
        day.roll(now);
        boolean hourLeft = hour.has(limits.perHour());
        boolean dayLeft = day.has(limits.perDay());
        if (hourLeft && dayLeft) {
            hour.used++;
            day.used++;
            return true;
        }
        if (!hourLeft) {
            hour.warnOnce(limits.perHour());
        }
        if (!dayLeft) {
            day.warnOnce(limits.perDay());
        }
        return false;
    }

    private static final class Window {

        private final ChronoUnit unit;
        private final String name;
        private Instant start;
        private int used;
        private boolean warned;

        private Window(ChronoUnit unit, String name) {
            this.unit = unit;
            this.name = name;
        }

        private void roll(Instant now) {
            Instant current = now.truncatedTo(unit);
            if (!current.equals(start)) {
                start = current;
                used = 0;
                warned = false;
            }
        }

        private boolean has(int limit) {
            return used < limit;
        }

        private void warnOnce(int limit) {
            if (!warned) {
                warned = true;
                log.warn("ai_cap_reached window={} limit={} since={}", name, limit, start);
            }
        }
    }
}
