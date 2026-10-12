package dev.tip.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Now, today and their bounds in an owner's time zone. Stored times stay UTC. */
record OwnerDay(ZoneId zone, ZonedDateTime now) {

    static OwnerDay of(Clock clock, ZoneId zone) {
        return new OwnerDay(zone, clock.instant().atZone(zone));
    }

    LocalDate today() {
        return now.toLocalDate();
    }

    /** Local midnight, or the first moment of the day where a clock change skips midnight. */
    Instant startOfToday() {
        return today().atStartOfDay(zone).toInstant();
    }

    /** Local midnight {@code days} days before today. */
    Instant startOfDayBefore(int days) {
        return today().minusDays(days).atStartOfDay(zone).toInstant();
    }

    Instant instant() {
        return now.toInstant();
    }
}
