package dev.tip.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class FakeDevicePartsTest {

    @Test
    void backoffDoublesFromOneSecondAndStaysAt60Seconds() {
        Backoff backoff = new Backoff(() -> 0);
        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            seconds.add(backoff.next(Duration.ZERO).toSeconds());
        }
        assertThat(seconds).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L);
    }

    @Test
    void backoffStartsOverOnlyAfterAConnectionStayedUpAMinute() {
        Backoff backoff = new Backoff(() -> 0);
        backoff.next(Duration.ZERO);
        backoff.next(Duration.ZERO);

        assertThat(backoff.next(Duration.ofSeconds(59))).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.next(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void backoffJitterShortensByAtMost20Percent() {
        Backoff backoff = new Backoff(() -> 0.999_999);
        assertThat(backoff.next(Duration.ZERO)).isEqualTo(Duration.ofMillis(800));
        assertThat(backoff.next(Duration.ZERO)).isEqualTo(Duration.ofMillis(1600));
    }

    @Test
    void recentIdsRemembersTheLast16() {
        RecentIds recent = new RecentIds();
        for (int i = 0; i < RecentIds.CAPACITY; i++) {
            assertThat(recent.add("tip-" + i)).isTrue();
        }
        assertThat(recent.add("tip-0")).isFalse();
        assertThat(recent.add("tip-15")).isFalse();

        assertThat(recent.add("tip-16")).isTrue();
        assertThat(recent.add("tip-0")).as("the oldest id was forgotten").isTrue();
        assertThat(recent.add("tip-2")).isFalse();
    }

    @Test
    void patternsHaveTheProtocolLengths() {
        assertThat(LedPattern.WARM.length(1)).isEqualTo(Duration.ofMillis(3600));
        assertThat(LedPattern.PLAYFUL.length(1)).isEqualTo(Duration.ofMillis(2890));
        assertThat(LedPattern.REQUEST.length(1)).isEqualTo(Duration.ofMillis(2500));
        assertThat(LedPattern.PLAIN.length(1)).isEqualTo(Duration.ofMillis(700));

        assertThat(LedPattern.WARM.length(5)).isEqualTo(Duration.ofMillis(8400));
        assertThat(LedPattern.PLAYFUL.length(5)).isEqualTo(Duration.ofMillis(9170));
        assertThat(LedPattern.REQUEST.length(5)).isEqualTo(Duration.ofMillis(8100));
        assertThat(LedPattern.PLAIN.length(5)).isEqualTo(Duration.ofMillis(4700));
        assertThat(LedPattern.PLAIN.length(2)).as("a 300 ms gap between blinks").isEqualTo(Duration.ofMillis(1700));
    }

    @ParameterizedTest
    @EnumSource(LedPattern.class)
    void everyPatternAddsOneUnitPerRepeatAndStaysWithin10Seconds(LedPattern pattern) {
        for (int repeats = 2; repeats <= LedPattern.MAX_REPEATS; repeats++) {
            assertThat(pattern.units(repeats)).isEqualTo(pattern.units(1) + repeats - 1);
            assertThat(pattern.length(repeats)).isGreaterThan(pattern.length(repeats - 1));
        }
        assertThat(pattern.length(LedPattern.MAX_REPEATS)).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void picturesAtOneRepeatAreTheProtocolPatterns() {
        assertThat(LedPattern.WARM.units(1)).isEqualTo(3);
        assertThat(LedPattern.PLAYFUL.units(1)).isEqualTo(2);
        assertThat(LedPattern.REQUEST.units(1)).isEqualTo(2);
        assertThat(LedPattern.PLAIN.units(1)).isEqualTo(1);
        assertThat(LedPattern.WARM.picture(1)).isEqualTo("_/^\\_ _/^\\_ _/^\\_");
        assertThat(LedPattern.REQUEST.picture(1)).isEqualTo("/^^^^\\_ /^^^^\\");
        assertThat(LedPattern.PLAIN.picture(3)).isEqualTo("[#######] [#######] [#######]");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{}|1",
                "{\"repeats\":null}|1",
                "{\"repeats\":\"3\"}|1",
                "{\"repeats\":2.5}|1",
                "{\"repeats\":0}|1",
                "{\"repeats\":-7}|1",
                "{\"repeats\":1}|1",
                "{\"repeats\":3}|3",
                "{\"repeats\":5}|5",
                "{\"repeats\":6}|5",
                "{\"repeats\":99999999999999999999}|5"
            })
    void missingRepeatsMeansOneAndOtherValuesAreClamped(String json, int repeats) {
        assertThat(FakeDevice.repeats(JsonMapper.builder().build().readTree(json)))
                .isEqualTo(repeats);
    }

    @Test
    void playerQueueKeeps8WaitingAndDropsTheOldestWaitingOne() {
        Player player = new Player(new PrintStream(new ByteArrayOutputStream()), 1);
        for (int i = 1; i <= Player.QUEUE_SIZE; i++) {
            assertThat(player.enqueue(new Player.Reaction("tip-" + i, LedPattern.PLAIN, 1)))
                    .isNull();
        }

        assertThat(Player.QUEUE_SIZE).isEqualTo(8);
        Player.Reaction dropped = player.enqueue(new Player.Reaction("tip-9", LedPattern.PLAIN, 1));

        assertThat(dropped.tipId()).isEqualTo("tip-1");
        assertThat(player.waiting()).isEqualTo(Player.QUEUE_SIZE);
    }

    @Test
    void repeatedReactionIsNotQueuedAgain() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FakeDevice device = new FakeDevice(
                URI.create("ws://localhost:1/ws/device/" + UUID.randomUUID()),
                "secret",
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new Backoff(() -> 0),
                1);
        String tipId = UUID.randomUUID().toString();
        String reaction = "{\"type\":\"reaction\",\"id\":\"" + tipId + "\",\"reaction\":\"WARM\",\"at\":\"x\"}";

        device.handle(reaction);
        device.handle(reaction);
        device.handle("{\"type\":\"reaction\",\"id\":\"" + UUID.randomUUID() + "\",\"reaction\":\"PURPLE\"}");
        device.handle("not json");

        // The unknown kind is queued too, as PLAIN; the repeat and the broken message are not.
        assertThat(device.waitingReactions()).isEqualTo(2);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("Repeat of tip " + tipId);
    }

    @Test
    void optionsBuildTheDeviceEndpointAndFallBackToTheEnvironmentSecret() {
        UUID device = UUID.randomUUID();
        PrintStream err = new PrintStream(new ByteArrayOutputStream());

        Optional<FakeDevice.Options> options = FakeDevice.Options.parse(
                new String[] {"--url=wss://tip.example.com/", "--device=" + device},
                Map.of("TIP_DEMO_DEVICE_SECRET", "from-env"),
                err);

        assertThat(options)
                .contains(new FakeDevice.Options(URI.create("wss://tip.example.com/ws/device/" + device), "from-env"));
        assertThat(FakeDevice.Options.parse(new String[] {"--device=" + device, "--secret=given"}, Map.of(), err)
                        .map(FakeDevice.Options::endpoint))
                .contains(URI.create("ws://localhost:8080/ws/device/" + device));
        assertThat(FakeDevice.Options.parse(new String[] {"--device=" + device}, Map.of(), err))
                .isEmpty();
        assertThat(FakeDevice.Options.parse(new String[] {"--device=nope", "--secret=s"}, Map.of(), err))
                .isEmpty();
        assertThat(FakeDevice.Options.parse(
                        new String[] {"--url=http://localhost", "--device=" + device, "--secret=s"}, Map.of(), err))
                .isEmpty();
        assertThat(FakeDevice.Options.parse(new String[] {"--colour=red"}, Map.of(), err))
                .isEmpty();
    }
}
