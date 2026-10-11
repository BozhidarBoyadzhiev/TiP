package dev.tip.dashboard;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The checked query parameters of the owner's tip list. Every parameter arrives as text and is parsed
 * here, so a bad value becomes a 400 that names the parameter instead of a generic conversion error. A
 * blank parameter counts as absent. {@code null} means no filter.
 */
record TipHistoryQuery(int page, int size, String status, String kind, Boolean flagged, LocalDate from, LocalDate to) {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    /** Statuses where money moved. {@code CREATED}, {@code FAILED} and {@code ABANDONED} were never tips. */
    static final List<String> VISIBLE_STATUSES = List.of("CAPTURED", "PENDING", "REFUNDED", "REVERSED");

    static final List<String> KINDS = List.of("WARM", "PLAYFUL", "REQUEST", "PLAIN");

    /** {@link UUID#fromString} also accepts short groups such as {@code 1-2-3-4-5}. */
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    static TipHistoryQuery parse(
            String page, String size, String status, String kind, String flagged, String from, String to) {
        TipHistoryQuery query = new TipHistoryQuery(
                wholeNumber("page", page, 0, 0, Integer.MAX_VALUE),
                wholeNumber("size", size, DEFAULT_SIZE, 1, MAX_SIZE),
                oneOf("status", status, VISIBLE_STATUSES),
                oneOf("kind", kind, KINDS),
                trueOrFalse(flagged),
                date("from", from),
                date("to", to));
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
            throw new DashboardExceptions.InvalidParameter("Parameter 'from' must not be after 'to'.");
        }
        return query;
    }

    static UUID tipId(String id) {
        if (id == null || !UUID_TEXT.matcher(id).matches()) {
            throw new DashboardExceptions.InvalidParameter("Parameter 'id' must be a UUID.");
        }
        return UUID.fromString(id);
    }

    /** Rows to skip. A long, because a large page times the size overflows an int. */
    long offset() {
        return (long) page * size;
    }

    private static int wholeNumber(String name, String value, int absent, int min, int max) {
        if (isBlank(value)) {
            return absent;
        }
        try {
            int number = Integer.parseInt(value.strip());
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException e) {
            // falls through to the same answer as an out of range number
        }
        String range = max == Integer.MAX_VALUE ? min + " or more" : "from " + min + " to " + max;
        throw new DashboardExceptions.InvalidParameter(
                "Parameter '" + name + "' must be a whole number " + range + ".");
    }

    private static String oneOf(String name, String value, List<String> allowed) {
        if (isBlank(value)) {
            return null;
        }
        String trimmed = value.strip();
        if (!allowed.contains(trimmed)) {
            throw new DashboardExceptions.InvalidParameter(
                    "Parameter '" + name + "' must be one of " + String.join(", ", allowed) + ".");
        }
        return trimmed;
    }

    private static Boolean trueOrFalse(String value) {
        if (isBlank(value)) {
            return null;
        }
        return switch (value.strip()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new DashboardExceptions.InvalidParameter("Parameter 'flagged' must be true or false.");
        };
    }

    /** ISO dates only. {@link LocalDate#parse} is strict, so 2026-02-30 is rejected rather than moved. */
    private static LocalDate date(String name, String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new DashboardExceptions.InvalidParameter("Parameter '" + name + "' must be a date like 2026-10-10.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
