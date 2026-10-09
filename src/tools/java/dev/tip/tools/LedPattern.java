package dev.tip.tools;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** The reaction patterns of {@code docs/DEVICE_PROTOCOL.md}, with a text picture of each. */
enum LedPattern {
    POSITIVE("heartbeat x3", "_/^\\_ _/^\\_ _/^\\_", repeat(3, 400, 600, 200)),
    NEGATIVE("harsh strobe 12 + 12", "|_|_|_|_|_|_|_|_|_|_|_|_   |_|_|_|_|_|_|_|_|_|_|_|_", strobe()),
    NEUTRAL("one solid blink", "[#######]", List.of(700));

    /** Step lengths in milliseconds: fades, on, off and pauses, in order. */
    private final List<Integer> stepsMillis;

    final String label;
    final String picture;

    LedPattern(String label, String picture, List<Integer> stepsMillis) {
        this.label = label;
        this.picture = picture;
        this.stepsMillis = stepsMillis;
    }

    Duration length() {
        return Duration.ofMillis(
                stepsMillis.stream().mapToInt(Integer::intValue).sum());
    }

    private static List<Integer> repeat(int times, Integer... steps) {
        return Collections.nCopies(times, List.of(steps)).stream()
                .flatMap(List::stream)
                .toList();
    }

    /** 12 flashes of 60 ms on and 60 ms off, a 250 ms pause, then 12 more. */
    private static List<Integer> strobe() {
        List<Integer> flashes = repeat(12, 60, 60);
        return Stream.of(flashes, List.of(250), flashes).flatMap(List::stream).toList();
    }
}
