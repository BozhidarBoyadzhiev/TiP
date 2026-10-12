package dev.tip.dashboard;

import dev.tip.dashboard.DashboardRepository.TipFact;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The owner's today, from local midnight to now in their time zone. Totals count only {@code CAPTURED}
 * money; the tip count, the request count and the hours count {@code CAPTURED} and {@code PENDING} tips,
 * the request count only classified, unflagged requests among them. A refunded request still shows in the
 * requests panel, which keeps every visible status, but is not one of today's requests.
 *
 * <p>Hours run from the first tip's local hour to the current hour, with at least {@link #MIN_HOURS}
 * buckets, so early in the day they reach back before midnight, where they count nothing. Each bucket is a
 * local hour built from {@link ZonedDateTime}, which steps on the instant time line: the day the clocks go
 * forward has 23 buckets from midnight, the day they go back 25, and the repeated hour shows twice with its
 * two offsets. On a tie the later hour is the busiest.
 */
record TodayReport(
        LocalDate date,
        String timeZone,
        List<Total> totals,
        long tipCount,
        long requestCount,
        List<Hour> hours,
        Hour busiestHour) {

    static final int MIN_HOURS = 8;

    private static final Set<String> COUNTED = Set.of("CAPTURED", "PENDING");

    static TodayReport of(OwnerDay day, List<TipFact> tips) {
        Map<String, Long> totals = new TreeMap<>();
        tips.stream()
                .filter(t -> t.status().equals("CAPTURED"))
                .forEach(t -> totals.merge(t.currency(), t.amountMinor(), Long::sum));
        List<Instant> counted = tips.stream()
                .filter(t -> COUNTED.contains(t.status()))
                .map(TipFact::createdAt)
                .toList();
        long requests = tips.stream()
                .filter(t -> t.request() && COUNTED.contains(t.status()))
                .count();
        List<Hour> hours = counted.isEmpty() ? null : hours(day, counted);
        return new TodayReport(
                day.today(),
                day.zone().getId(),
                totals.entrySet().stream()
                        .map(e -> new Total(e.getKey(), e.getValue()))
                        .toList(),
                counted.size(),
                requests,
                hours,
                hours == null ? null : busiest(hours));
    }

    private static List<Hour> hours(OwnerDay day, List<Instant> tips) {
        ZonedDateTime current = day.now().truncatedTo(ChronoUnit.HOURS);
        ZonedDateTime first = tips.stream()
                .min(Instant::compareTo)
                .orElseThrow()
                .atZone(day.zone())
                .truncatedTo(ChronoUnit.HOURS);
        ZonedDateTime earliest = current.minusHours(MIN_HOURS - 1);
        List<Hour> hours = new ArrayList<>();
        for (ZonedDateTime start = first.isBefore(earliest) ? first : earliest;
                !start.isAfter(current);
                start = start.plusHours(1)) {
            Instant from = start.toInstant();
            Instant to = start.plusHours(1).toInstant();
            long count = tips.stream()
                    .filter(t -> !t.isBefore(from) && t.isBefore(to))
                    .count();
            hours.add(new Hour(start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), start.getHour(), count));
        }
        return hours;
    }

    private static Hour busiest(List<Hour> hours) {
        Hour busiest = hours.getFirst();
        for (Hour hour : hours) {
            if (hour.count() >= busiest.count()) {
                busiest = hour;
            }
        }
        return busiest;
    }

    record Total(String currency, long amountMinor) {}

    /** {@code start} is the local hour with its offset, {@code hour} its local hour of the day, 0 to 23. */
    record Hour(String start, int hour, long count) {}
}
