package dev.tip.dashboard;

import dev.tip.dashboard.DashboardRepository.RequestRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What people asked for over the last {@link #DAYS} days in the owner's zone, today included. The top song
 * is the song key asked for most, at least twice, shown with its newest spelling; a tie goes to the key
 * asked for most recently. Groups come from today's digest when there is one ({@code grouped} is {@code
 * ai}); notes the digest has not seen, or every note when there is no digest ({@code exact}), are grouped
 * by their text, ignoring case and whitespace. Groups are newest first, tip ids newest first.
 */
record RequestsReport(LocalDate from, LocalDate to, TopSong topSong, String grouped, List<Group> groups) {

    static final int DAYS = 7;
    static final int MIN_SONG_REQUESTS = 2;

    static final String AI = "ai";
    static final String EXACT = "exact";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * @param rows the window's unflagged requests, newest first
     * @param aiGroups today's digest groups, or null without a digest
     */
    static RequestsReport of(OwnerDay day, List<RequestRow> rows, List<StoredGroup> aiGroups) {
        List<RequestRow> noted =
                rows.stream().filter(r -> r.ownerNote() != null).toList();
        Map<UUID, RequestRow> byId = noted.stream().collect(Collectors.toMap(RequestRow::tipId, r -> r));
        List<Group> groups = new ArrayList<>();
        Set<UUID> grouped = new HashSet<>();
        if (aiGroups != null) {
            for (StoredGroup aiGroup : aiGroups) {
                List<RequestRow> members = aiGroup.tipIds().stream()
                        .map(byId::get)
                        .filter(r -> r != null && grouped.add(r.tipId()))
                        .toList();
                if (!members.isEmpty()) {
                    groups.add(group(aiGroup.label(), members));
                }
            }
        }
        Map<String, List<RequestRow>> exact = new LinkedHashMap<>();
        noted.stream()
                .filter(r -> !grouped.contains(r.tipId()))
                .forEach(r -> exact.computeIfAbsent(normalise(r.ownerNote()), key -> new ArrayList<>())
                        .add(r));
        exact.values().forEach(members -> groups.add(group(members.getFirst().ownerNote(), members)));
        groups.sort(Comparator.comparing(Group::latestAt).reversed());
        return new RequestsReport(
                day.today().minusDays(DAYS - 1), day.today(), topSong(rows), aiGroups == null ? EXACT : AI, groups);
    }

    /** Members are newest first, so the first is the latest. */
    private static Group group(String label, List<RequestRow> members) {
        List<RequestRow> sorted = members.stream()
                .sorted(Comparator.comparing(RequestRow::createdAt)
                        .reversed()
                        .thenComparing(RequestRow::tipId, Comparator.reverseOrder()))
                .toList();
        return new Group(
                label,
                sorted.size(),
                sorted.getFirst().createdAt(),
                sorted.stream().map(RequestRow::tipId).toList());
    }

    private static TopSong topSong(List<RequestRow> rows) {
        Map<String, List<RequestRow>> byKey = new HashMap<>();
        rows.stream()
                .filter(r -> r.songKey() != null && r.song() != null)
                .forEach(r -> byKey.computeIfAbsent(r.songKey(), key -> new ArrayList<>())
                        .add(r));
        return byKey.values().stream()
                .filter(requests -> requests.size() >= MIN_SONG_REQUESTS)
                .max(Comparator.<List<RequestRow>>comparingInt(List::size)
                        .thenComparing(requests -> requests.getFirst().createdAt()))
                .map(requests -> new TopSong(
                        requests.getFirst().song(),
                        requests.size(),
                        requests.getFirst().createdAt()))
                .orElse(null);
    }

    private static String normalise(String note) {
        return WHITESPACE.matcher(note.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    /** {@code title} is the newest spelling of the most asked song. */
    record TopSong(String title, long count, Instant latestAt) {}

    record Group(String label, long count, Instant latestAt, List<UUID> tipIds) {}

    /** A request group as the digest stored it. */
    record StoredGroup(String label, List<UUID> tipIds) {}
}
