package dev.tip.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import dev.tip.ai.api.TipReacted;
import dev.tip.devices.DeviceSessionProbe;
import dev.tip.devices.ResendJobRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The fake device against the real device channel. */
class FakeDeviceTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ResendJobRunner resendJob;

    @Autowired
    DeviceSessionProbe probe;

    @Test
    void playsAndAcksAReaction() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Fake Panda", secret);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FakeDevice device = fakeDevice(deviceId, secret, bytes);
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(device::run);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> output(bytes).contains("Connected as \"Fake Panda\""));
            UUID tipId = UUID.randomUUID();

            transactions.executeWithoutResult(
                    status -> events.publishEvent(new TipReacted(tipId, deviceId, Reaction.WARM, 1, clock.instant())));

            await().atMost(Duration.ofSeconds(10)).until(() -> acked(tipId));
            assertThat(output(bytes)).contains("WARM", "heartbeat x3", "repeats 1", "tip " + tipId);
        } finally {
            device.stop();
        }
        assertThat(exit.get(10, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void acksARepeatWithoutPlayingItAgain() throws Exception {
        clock.pin(Instant.now().truncatedTo(ChronoUnit.MICROS));
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Fake Panda", secret);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FakeDevice device = fakeDevice(deviceId, secret, bytes);
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(device::run);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> output(bytes).contains("Connected as \"Fake Panda\""));
            UUID tipId = UUID.randomUUID();
            transactions.executeWithoutResult(status ->
                    events.publishEvent(new TipReacted(tipId, deviceId, Reaction.PLAYFUL, 1, clock.instant())));
            await().atMost(Duration.ofSeconds(10)).until(() -> acked(tipId));

            // As if the first ack was lost: the server sends the reaction again.
            jdbc.sql("update reaction_delivery set acked_at = null where tip_id = :id")
                    .param("id", tipId)
                    .update();
            clock.advance(ResendJobRunner.RESEND_INTERVAL);
            resendJob.resend();

            await().atMost(Duration.ofSeconds(10)).until(() -> acked(tipId));
            assertThat(output(bytes)).contains("Repeat of tip " + tipId + ", not played again, acked again");
            assertThat(output(bytes).split("giggle x2", -1)).hasSize(2);
        } finally {
            device.stop();
        }
        assertThat(exit.get(10, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void acksOnReceiptBeforePlaybackAndPlaysQueuedReactionsInOrder() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Fake Panda", secret);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FakeDevice device = fakeDevice(deviceId, secret, bytes);
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(device::run);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> output(bytes).contains("Connected as \"Fake Panda\""));
            UUID longest = UUID.randomUUID();
            UUID queued = UUID.randomUUID();

            transactions.executeWithoutResult(status ->
                    events.publishEvent(new TipReacted(longest, deviceId, Reaction.PLAYFUL, 5, clock.instant())));
            await().atMost(Duration.ofSeconds(3)).until(() -> acked(longest));
            transactions.executeWithoutResult(status ->
                    events.publishEvent(new TipReacted(queued, deviceId, Reaction.PLAIN, 2, clock.instant())));

            // The first pattern runs 9.17 s, so both acks arriving well within it means ack on receipt.
            await().atMost(Duration.ofSeconds(3)).until(() -> acked(queued));
            assertThat(output(bytes))
                    .contains("giggle x6", "repeats 5", "tip " + longest)
                    .doesNotContain("tip " + queued);

            await().atMost(Duration.ofSeconds(15)).until(() -> output(bytes).contains("tip " + queued));
            String played = output(bytes);
            assertThat(played).contains("blink x2", "repeats 2");
            assertThat(played.indexOf("tip " + longest)).isLessThan(played.indexOf("tip " + queued));
        } finally {
            device.stop();
        }
        assertThat(exit.get(10, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void acksAnUnknownKindAndPlaysItAsPlainOnce() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Fake Panda", secret);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FakeDevice device = fakeDevice(deviceId, secret, bytes);
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(device::run);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> output(bytes).contains("Connected as \"Fake Panda\""));
            await().atMost(Duration.ofSeconds(5)).until(() -> probe.isConnected(deviceId));
            UUID tipId = UUID.randomUUID();
            // The server only ever sends the four kinds, so the delivery row is stored with one of them and
            // the unknown kind goes straight to the device's session.
            jdbc.sql("""
                            insert into reaction_delivery (tip_id, device_id, reaction, repeats, reacted_at)
                            values (:tipId, :deviceId, 'PLAIN', 1, :at)""")
                    .param("tipId", tipId)
                    .param("deviceId", deviceId)
                    .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                    .update();

            probe.sendRaw(
                    deviceId,
                    "{\"type\":\"reaction\",\"id\":\"" + tipId
                            + "\",\"reaction\":\"SPARKLY\",\"repeats\":4,\"at\":\"2026-10-13T14:02:11Z\"}");

            await().atMost(Duration.ofSeconds(10)).until(() -> acked(tipId));
            await().atMost(Duration.ofSeconds(5)).until(() -> output(bytes).contains("blink x1"));
            assertThat(output(bytes))
                    .contains("Unknown kind from tip " + tipId + ", acked, playing PLAIN with repeats 1")
                    .contains("PLAIN", "blink x1", "repeats 1", "tip " + tipId);
        } finally {
            device.stop();
        }
        assertThat(exit.get(10, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void replacedDeviceSaysWhyAndWaitsAMinuteBeforeReconnecting() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Fake Panda", secret);
        ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBytes = new ByteArrayOutputStream();
        FakeDevice first = fakeDevice(deviceId, secret, firstBytes);
        FakeDevice second = fakeDevice(deviceId, secret, secondBytes);
        CompletableFuture<Integer> firstExit = CompletableFuture.supplyAsync(first::run);
        CompletableFuture<Integer> secondExit = null;
        try {
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> output(firstBytes).contains("Connected as \"Fake Panda\""));
            secondExit = CompletableFuture.supplyAsync(second::run);
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> output(secondBytes).contains("Connected as \"Fake Panda\""));

            // The backoff here gives 1 s without jitter, so 60 s can only come from the replaced rule.
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> output(firstBytes).contains("Reconnecting in"));
            assertThat(output(firstBytes))
                    .contains(
                            "Server is closing the connection: replaced",
                            "Replaced by another connection as this device",
                            "Reconnecting in 60.0 s");
            assertThat(output(secondBytes)).doesNotContain("closing");
        } finally {
            first.stop();
            second.stop();
        }
        assertThat(firstExit.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(secondExit.get(10, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void rejectedSecretStopsWithoutReconnecting() throws Exception {
        UUID deviceId = data.device(TestData.uniqueSlug(), "Fake Panda", TestData.randomSecret());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int exit = CompletableFuture.supplyAsync(fakeDevice(deviceId, TestData.randomSecret(), bytes)::run)
                .get(10, TimeUnit.SECONDS);

        assertThat(exit).isEqualTo(FakeDevice.EXIT_AUTH_REJECTED);
        assertThat(output(bytes)).contains("Auth rejected (401)").doesNotContain("Reconnecting");
    }

    private FakeDevice fakeDevice(UUID deviceId, String secret, ByteArrayOutputStream bytes) {
        return new FakeDevice(
                URI.create("ws://localhost:" + port + "/ws/device/" + deviceId),
                secret,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new Backoff(() -> 0));
    }

    private boolean acked(UUID tipId) {
        return jdbc.sql("select acked_at is not null from reaction_delivery where tip_id = :id")
                .param("id", tipId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /** ByteArrayOutputStream writes and reads under its own lock, so this sees whole lines. */
    private static String output(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
