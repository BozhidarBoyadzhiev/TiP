package dev.tip.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import dev.tip.ai.api.TipReacted;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
                    status -> events.publishEvent(new TipReacted(tipId, deviceId, Reaction.POSITIVE, clock.instant())));

            await().atMost(Duration.ofSeconds(10)).until(() -> acked(tipId));
            assertThat(output(bytes)).contains("POSITIVE", "heartbeat x3", "tip " + tipId);
        } finally {
            device.stop();
        }
        assertThat(exit.get(10, TimeUnit.SECONDS)).isZero();
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
