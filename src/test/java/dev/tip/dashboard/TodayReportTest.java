package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.dashboard.DashboardRepository.TipFact;
import dev.tip.dashboard.TodayReport.Hour;
import dev.tip.dashboard.TodayReport.Total;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodayReportTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @Test
    void anEmptyDayHasNoHoursAndNothingCounted() {
        TodayReport report = TodayReport.of(day(LONDON, "2026-10-12T15:00:00Z"), List.of());

        assertThat(report.date()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(report.timeZone()).isEqualTo("Europe/London");
        assertThat(report.totals()).isEmpty();
        assertThat(report.tipCount()).isZero();
        assertThat(report.requestCount()).isZero();
        assertThat(report.hours()).isNull();
        assertThat(report.busiestHour()).isNull();
    }

    @Test
    void onlyCapturedMoneyCountsAndTipsAndRequestsCountOnlyCapturedAndPending() {
        TodayReport report = TodayReport.of(
                day(LONDON, "2026-10-12T15:00:00Z"),
                List.of(
                        tip("2026-10-12T09:00:00Z", 500, "GBP", "CAPTURED", false),
                        tip("2026-10-12T09:10:00Z", 200, "GBP", "CAPTURED", true),
                        tip("2026-10-12T09:20:00Z", 300, "EUR", "CAPTURED", false),
                        tip("2026-10-12T09:30:00Z", 1000, "GBP", "PENDING", true),
                        tip("2026-10-12T09:40:00Z", 2000, "GBP", "REFUNDED", true),
                        tip("2026-10-12T09:50:00Z", 4000, "GBP", "REVERSED", true)));

        assertThat(report.totals()).containsExactly(new Total("EUR", 300), new Total("GBP", 700));
        assertThat(report.tipCount()).isEqualTo(4);
        assertThat(report.requestCount()).isEqualTo(2);
        assertThat(report.hours().stream().mapToLong(Hour::count).sum()).isEqualTo(4);
    }

    @Test
    void hoursRunFromTheFirstTipToNowInTheOwnersZone() {
        TodayReport report = TodayReport.of(
                day(LONDON, "2026-10-12T19:30:00Z"),
                List.of(
                        tip("2026-10-12T05:10:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-12T17:05:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-12T17:55:00Z", 100, "GBP", "CAPTURED", false)));

        assertThat(report.hours()).extracting(Hour::hour).containsExactly(range(6, 20));
        assertThat(report.hours().getFirst().start()).isEqualTo("2026-10-12T06:00:00+01:00");
        assertThat(report.hours().getLast().start()).isEqualTo("2026-10-12T20:00:00+01:00");
        assertThat(report.busiestHour()).isEqualTo(new Hour("2026-10-12T18:00:00+01:00", 18, 2));
    }

    @Test
    void earlyInTheDayTheEightHoursReachBackBeforeMidnightAndCountNothingThere() {
        TodayReport report = TodayReport.of(
                day(LONDON, "2026-10-12T01:40:00Z"),
                List.of(tip("2026-10-12T00:15:00Z", 100, "GBP", "CAPTURED", false)));

        assertThat(report.hours()).hasSize(TodayReport.MIN_HOURS);
        assertThat(report.hours()).extracting(Hour::hour).containsExactly(19, 20, 21, 22, 23, 0, 1, 2);
        assertThat(report.hours()).extracting(Hour::count).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L);
        assertThat(report.busiestHour().hour()).isEqualTo(1);
    }

    @Test
    void onATieTheLaterHourIsTheBusiest() {
        TodayReport report = TodayReport.of(
                day(LONDON, "2026-10-12T19:30:00Z"),
                List.of(
                        tip("2026-10-12T09:10:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-12T09:20:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-12T13:10:00Z", 100, "GBP", "PENDING", false),
                        tip("2026-10-12T13:20:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-12T15:20:00Z", 100, "GBP", "CAPTURED", false)));

        assertThat(report.busiestHour()).isEqualTo(new Hour("2026-10-12T14:00:00+01:00", 14, 2));
    }

    @Test
    void theDayTheClocksGoForwardHas23Hours() {
        // 29 March 2026: 01:00 GMT becomes 02:00 BST, so there is no 1 am.
        TodayReport report = TodayReport.of(
                day(LONDON, "2026-03-29T22:30:00Z"),
                List.of(
                        tip("2026-03-29T00:30:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-03-29T01:30:00Z", 100, "GBP", "CAPTURED", false)));

        assertThat(report.hours()).hasSize(23);
        assertThat(report.hours()).extracting(Hour::hour).doesNotContain(1).startsWith(0, 2, 3);
        assertThat(report.hours().get(0).start()).isEqualTo("2026-03-29T00:00:00Z");
        assertThat(report.hours().get(1)).isEqualTo(new Hour("2026-03-29T02:00:00+01:00", 2, 1));
        assertThat(report.hours().getLast().start()).isEqualTo("2026-03-29T23:00:00+01:00");
    }

    @Test
    void theDayTheClocksGoBackHas25HoursWithOneAmTwice() {
        // 25 October 2026: 02:00 BST becomes 01:00 GMT, so 1 am comes twice.
        TodayReport report = TodayReport.of(
                day(LONDON, "2026-10-25T23:30:00Z"),
                List.of(
                        tip("2026-10-24T23:05:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-25T00:10:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-25T01:10:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-25T01:20:00Z", 100, "GBP", "CAPTURED", false)));

        assertThat(report.hours()).hasSize(25);
        assertThat(report.hours().subList(0, 4))
                .containsExactly(
                        new Hour("2026-10-25T00:00:00+01:00", 0, 1),
                        new Hour("2026-10-25T01:00:00+01:00", 1, 1),
                        new Hour("2026-10-25T01:00:00Z", 1, 2),
                        new Hour("2026-10-25T02:00:00Z", 2, 0));
        assertThat(report.busiestHour().start()).isEqualTo("2026-10-25T01:00:00Z");
    }

    @Test
    void aZoneWithAHalfHourOffsetGetsItsOwnHourBoundaries() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        TodayReport report = TodayReport.of(
                day(kolkata, "2026-10-12T10:00:00Z"),
                List.of(
                        tip("2026-10-12T09:25:00Z", 100, "GBP", "CAPTURED", false),
                        tip("2026-10-12T09:35:00Z", 100, "GBP", "CAPTURED", false)));

        assertThat(report.hours().getLast()).isEqualTo(new Hour("2026-10-12T15:00:00+05:30", 15, 1));
        assertThat(report.hours().get(report.hours().size() - 2))
                .isEqualTo(new Hour("2026-10-12T14:00:00+05:30", 14, 1));
    }

    private static OwnerDay day(ZoneId zone, String now) {
        return new OwnerDay(zone, Instant.parse(now).atZone(zone));
    }

    private static TipFact tip(String at, long amountMinor, String currency, String status, boolean request) {
        return new TipFact(Instant.parse(at), amountMinor, currency, status, request);
    }

    private static Integer[] range(int from, int to) {
        List<Integer> hours = new ArrayList<>();
        for (int h = from; h <= to; h++) {
            hours.add(h);
        }
        return hours.toArray(Integer[]::new);
    }
}
