package dev.tip.tools;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** The reaction patterns of {@code docs/DEVICE_PROTOCOL.md}, with a text picture of each. */
enum LedPattern {
    WARM("heartbeat x3", "_/^\\_ _/^\\_ _/^\\_", repeat(3, 400, 600, 200)),
    PLAYFUL("giggle 6 + 6 flicks", "^v^v^v^v^v^v   ^v^v^v^v^v^v", giggle()),
    REQUEST("noted x2", "/^^^^\\_ /^^^^\\", List.of(300, 500, 300, 300, 300, 500, 300)),
    PLAIN("one solid blink", "[#######]", List.of(700));

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

    /** Six flicks of 60 ms rising, 90 ms falling and a 70 ms gap, a 250 ms pause, then six more. */
    private static List<Integer> giggle() {
        List<Integer> flicks = repeat(6, 60, 90, 70);
        return Stream.of(flicks, List.of(250), flicks).flatMap(List::stream).toList();
    }
}
