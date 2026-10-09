package dev.tip.tools;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * The reaction patterns of {@code docs/DEVICE_PROTOCOL.md}, with a text picture of each. A pattern is a
 * unit played {@code base + (repeats - 1)} times, with a fixed gap between units.
 */
enum LedPattern {
    WARM("heartbeat", "_/^\\_", " ", 3, List.of(400, 600, 200), 0),
    PLAYFUL("giggle", "^v^v^v^v^v^v", "   ", 2, giggle(), 250),
    REQUEST("noted", "/^^^^\\", "_ ", 2, List.of(300, 500, 300), 300),
    PLAIN("blink", "[#######]", " ", 1, List.of(700), 300);

    static final int MIN_REPEATS = 1;
    static final int MAX_REPEATS = 5;

    final String label;
    private final String unitPicture;
    private final String gapPicture;
    private final int baseUnits;

    /** Step lengths of one unit in milliseconds: fades, on, off and pauses, in order. */
    private final List<Integer> unitMillis;

    private final int gapMillis;

    LedPattern(
            String label,
            String unitPicture,
            String gapPicture,
            int baseUnits,
            List<Integer> unitMillis,
            int gapMillis) {
        this.label = label;
        this.unitPicture = unitPicture;
        this.gapPicture = gapPicture;
        this.baseUnits = baseUnits;
        this.unitMillis = unitMillis;
        this.gapMillis = gapMillis;
    }

    /** Values outside 1 to 5 are clamped, not rejected. */
    static int clampRepeats(BigInteger repeats) {
        return repeats.max(BigInteger.valueOf(MIN_REPEATS))
                .min(BigInteger.valueOf(MAX_REPEATS))
                .intValue();
    }

    int units(int repeats) {
        return baseUnits + repeats - 1;
    }

    Duration length(int repeats) {
        int units = units(repeats);
        int unit = unitMillis.stream().mapToInt(Integer::intValue).sum();
        return Duration.ofMillis((long) units * unit + (long) (units - 1) * gapMillis);
    }

    String picture(int repeats) {
        return String.join(gapPicture, Collections.nCopies(units(repeats), unitPicture));
    }

    /** Six flicks of 60 ms rising, 90 ms falling and a 70 ms gap. */
    private static List<Integer> giggle() {
        return Collections.nCopies(6, List.of(60, 90, 70)).stream()
                .flatMap(List::stream)
                .toList();
    }
}
