package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import dev.tip.ai.api.TipReacted;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

class ObserverChannelTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ObserverSessions observers;

    @Test
    void observerReceivesOnlyTypeReactionAndAt() throws Exception {
        String slug = TestData.uniqueSlug();
        UUID deviceId = data.device(slug, "Observed Panda");

        try (TestSocket observer = TestSocket.connectObserver(port, slug, DeviceChannelTest.uniqueIp())) {
            await().atMost(Duration.ofSeconds(5)).until(() -> observers.isWatched(slug));
            transactions.executeWithoutResult(status -> events.publishEvent(new TipReacted(
                    UUID.randomUUID(), deviceId, Reaction.NEGATIVE, Instant.parse("2026-10-13T14:02:11.5Z"))));

            assertThat(observer.nextJson())
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("type", "reaction", "reaction", "NEGATIVE", "at", "2026-10-13T14:02:11Z"));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> !observers.isWatched(slug));
    }

    @Test
    void unknownOrMalformedSlugGets404() throws Exception {
        try (RawWebSocket unknown = observe(TestData.uniqueSlug(), DeviceChannelTest.uniqueIp());
                RawWebSocket malformed = observe("NO", DeviceChannelTest.uniqueIp())) {
            assertThat(unknown.status).isEqualTo(404);
            assertThat(malformed.status).isEqualTo(404);
        }
    }

    @Test
    void sixthConcurrentConnectionFromOneIpGets429AndClosingFreesAPlace() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Busy Panda");
        String ip = DeviceChannelTest.uniqueIp();
        List<TestSocket> open = new ArrayList<>();
        try {
            for (int i = 0; i < ObserverConnectionLimiter.MAX_PER_IP; i++) {
                open.add(TestSocket.connectObserver(port, slug, ip));
            }
            try (RawWebSocket sixth = observe(slug, ip)) {
                assertThat(sixth.status).isEqualTo(429);
            }
            open.removeFirst().close();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                try (RawWebSocket again = observe(slug, ip)) {
                    assertThat(again.status).isEqualTo(101);
                }
            });
        } finally {
            for (TestSocket socket : open) {
                socket.close();
            }
        }
    }

    @Test
    void wrongOriginIsRejected() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Panda");

        try (RawWebSocket response = RawWebSocket.handshake(
                port,
                "/ws/observe/" + slug,
                Map.of("Origin", "https://evil.example", "X-Forwarded-For", DeviceChannelTest.uniqueIp()))) {
            assertThat(response.status).isEqualTo(403);
        }
    }

    @Test
    void inboundMessagesAreIgnoredAndOversizeClosesWith1009() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Panda");

        try (TestSocket observer = TestSocket.connectObserver(port, slug, DeviceChannelTest.uniqueIp())) {
            for (int i = 0; i < 30; i++) {
                observer.send("{\"type\":\"ack\",\"id\":\"" + UUID.randomUUID() + "\"}");
            }
            observer.sync();
            assertThat(observer.isOpen()).isTrue();
            assertThat(observer.pending()).isZero();

            observer.send("x".repeat(DeviceChannelHandler.MAX_INBOUND_BYTES + 1));

            assertThat(observer.awaitClose().getCode()).isEqualTo(1009);
        }
    }

    private RawWebSocket observe(String slug, String ip) throws Exception {
        return RawWebSocket.handshake(
                port, "/ws/observe/" + slug, Map.of("Origin", TestSocket.PUBLIC_ORIGIN, "X-Forwarded-For", ip));
    }
}
