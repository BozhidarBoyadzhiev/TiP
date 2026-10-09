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

class FakeDevicePartsTest {

    @Test
    void backoffFollowsTheProtocolAndStaysAt30Seconds() {
        Backoff backoff = new Backoff(() -> 0);
        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            seconds.add(backoff.next().toSeconds());
        }
        assertThat(seconds).containsExactly(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L);

        backoff.reset();
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void backoffJitterIsAtMost20Percent() {
        Backoff backoff = new Backoff(() -> 0.999_999);
        assertThat(backoff.next()).isEqualTo(Duration.ofMillis(1200));
        assertThat(backoff.next()).isEqualTo(Duration.ofMillis(2400));
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
        assertThat(LedPattern.WARM.length()).isEqualTo(Duration.ofMillis(3600));
        assertThat(LedPattern.PLAYFUL.length()).isEqualTo(Duration.ofMillis(2890));
        assertThat(LedPattern.REQUEST.length()).isEqualTo(Duration.ofMillis(2500));
        assertThat(LedPattern.PLAIN.length()).isEqualTo(Duration.ofMillis(700));
    }

    @Test
    void playerQueueKeeps4AndDropsTheOldest() {
        Player player = new Player(id -> {}, new PrintStream(new ByteArrayOutputStream()));
        for (int i = 1; i <= Player.QUEUE_SIZE; i++) {
            assertThat(player.enqueue(new Player.Reaction("tip-" + i, LedPattern.PLAIN)))
                    .isNull();
        }

        Player.Reaction dropped = player.enqueue(new Player.Reaction("tip-5", LedPattern.PLAIN));

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
                new Backoff(() -> 0));
        String tipId = UUID.randomUUID().toString();
        String reaction = "{\"type\":\"reaction\",\"id\":\"" + tipId + "\",\"reaction\":\"WARM\",\"at\":\"x\"}";

        device.handle(reaction);
        device.handle(reaction);
        device.handle("{\"type\":\"reaction\",\"id\":\"" + UUID.randomUUID() + "\",\"reaction\":\"PURPLE\"}");
        device.handle("not json");

        assertThat(device.waitingReactions()).isEqualTo(1);
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
