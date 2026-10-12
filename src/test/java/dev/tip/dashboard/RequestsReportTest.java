package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.dashboard.DashboardRepository.RequestRow;
import dev.tip.dashboard.RequestsReport.Group;
import dev.tip.dashboard.RequestsReport.StoredGroup;
import dev.tip.dashboard.RequestsReport.TopSong;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RequestsReportTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final OwnerDay DAY =
            new OwnerDay(LONDON, Instant.parse("2026-10-12T19:00:00Z").atZone(LONDON));

    @Test
    void theWindowIsTheLastSevenLocalDays() {
        RequestsReport report = RequestsReport.of(DAY, List.of(), null);

        assertThat(report.from()).isEqualTo(LocalDate.parse("2026-10-06"));
        assertThat(report.to()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(report.topSong()).isNull();
        assertThat(report.groups()).isEmpty();
        assertThat(report.grouped()).isEqualTo("exact");
    }

    @Test
    void aSongAskedForOnceIsNoTopSong() {
        assertThat(RequestsReport.of(DAY, rows(song("10:00", "Wonderwall", "wonderwall")), null)
                        .topSong())
                .isNull();
    }

    @Test
    void theTopSongIsTheKeyAskedForMostWithItsNewestSpelling() {
        RequestRow newest = song("18:00", "wonderwall!", "wonderwall");
        RequestsReport report = RequestsReport.of(
                DAY,
                rows(
                        song("09:00", "The Wonderwall", "wonderwall"),
                        newest,
                        song("12:00", "Wonderwall", "wonderwall"),
                        song("17:00", "Despacito", "despacito"),
                        song("17:30", "despacito", "despacito")),
                null);

        assertThat(report.topSong()).isEqualTo(new TopSong("wonderwall!", 3, newest.createdAt()));
    }

    @Test
    void onATieTheSongAskedForMostRecentlyWins() {
        RequestsReport report = RequestsReport.of(
                DAY,
                rows(
                        song("09:00", "Wonderwall", "wonderwall"),
                        song("15:00", "Wonderwall", "wonderwall"),
                        song("10:00", "Despacito", "despacito"),
                        song("16:00", "Despacito", "despacito")),
                null);

        assertThat(report.topSong().title()).isEqualTo("Despacito");
    }

    @Test
    void withoutADigestIdenticalNotesAreGroupedIgnoringCaseAndWhitespaceNewestFirst() {
        RequestRow loud1 = note("10:00", "Feedback: too loud near the café");
        RequestRow loud2 = note("16:00", "feedback:  TOO loud near the café ");
        RequestRow queen = note("12:00", "Request: something by Queen");
        RequestsReport report = RequestsReport.of(DAY, rows(loud1, loud2, queen), null);

        assertThat(report.grouped()).isEqualTo("exact");
        assertThat(report.groups())
                .containsExactly(
                        new Group(loud2.ownerNote(), 2, loud2.createdAt(), List.of(loud2.tipId(), loud1.tipId())),
                        new Group(queen.ownerNote(), 1, queen.createdAt(), List.of(queen.tipId())));
    }

    @Test
    void theDigestsGroupsComeFirstAndNotesItHasNotSeenAreGroupedExactly() {
        RequestRow wonder1 = note("10:00", "Request: Wonderwall");
        RequestRow wonder2 = note("11:00", "Request: Wonderwall by Oasis");
        RequestRow later = note("18:30", "Request: Wonderwall please");
        RequestRow loud = note("12:00", "Feedback: too loud");
        UUID goneOrHidden = UUID.randomUUID();
        List<StoredGroup> ai = List.of(
                new StoredGroup("Play Wonderwall", List.of(wonder1.tipId(), wonder2.tipId())),
                new StoredGroup("Something no longer shown", List.of(goneOrHidden)),
                new StoredGroup("Too loud", List.of(loud.tipId(), wonder1.tipId())));

        RequestsReport report = RequestsReport.of(DAY, rows(wonder1, wonder2, later, loud), ai);

        assertThat(report.grouped()).isEqualTo("ai");
        assertThat(report.groups())
                .containsExactly(
                        new Group(later.ownerNote(), 1, later.createdAt(), List.of(later.tipId())),
                        new Group("Too loud", 1, loud.createdAt(), List.of(loud.tipId())),
                        new Group(
                                "Play Wonderwall", 2, wonder2.createdAt(), List.of(wonder2.tipId(), wonder1.tipId())));
        assertThat(report.groups()).flatExtracting(Group::tipIds).doesNotContain(goneOrHidden);
    }

    @Test
    void requestsWithoutANoteStillCountForTheSongButJoinNoGroup() {
        RequestRow a = new RequestRow(UUID.randomUUID(), at("10:00"), null, "Wonderwall", "wonderwall");
        RequestRow b = new RequestRow(UUID.randomUUID(), at("11:00"), null, "Wonderwall", "wonderwall");

        RequestsReport report = RequestsReport.of(DAY, rows(a, b), null);

        assertThat(report.topSong().count()).isEqualTo(2);
        assertThat(report.groups()).isEmpty();
    }

    /** Newest first, as the repository returns them. */
    private static List<RequestRow> rows(RequestRow... rows) {
        return Stream.of(rows)
                .sorted(Comparator.comparing(RequestRow::createdAt).reversed())
                .toList();
    }

    private static RequestRow song(String time, String song, String key) {
        return new RequestRow(UUID.randomUUID(), at(time), "Request: " + song, song, key);
    }

    private static RequestRow note(String time, String note) {
        return new RequestRow(UUID.randomUUID(), at(time), note, null, null);
    }

    private static Instant at(String time) {
        return Instant.parse("2026-10-12T" + time + ":00Z");
    }
}
