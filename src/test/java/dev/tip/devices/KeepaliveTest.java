package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketSession;

class KeepaliveTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-13T14:00:00Z");
    private static final Duration PING_INTERVAL = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    DeviceSessions sessions;

    @Autowired
    ObserverSessions observers;

    @Autowired
    SessionKeepalive keepalive;

    @Test
    void deviceThatStopsAnsweringPingsIsToldWhyAndClosedAfter45SecondsAndAnAnsweringOneStays() throws Exception {
        clock.pin(NOW);
        String frozenSecret = TestData.randomSecret();
        UUID frozenId = data.device(TestData.uniqueSlug(), "Frozen Panda", frozenSecret);
        String liveSecret = TestData.randomSecret();
        UUID liveId = data.device(TestData.uniqueSlug(), "Live Panda", liveSecret);

        try (RawWebSocket frozen = RawWebSocket.handshake(
                        port,
                        "/ws/device/" + frozenId,
                        Map.of(
                                "Authorization",
                                "Bearer " + frozenSecret,
                                "X-Forwarded-For",
                                DeviceChannelTest.uniqueIp()));
                TestSocket live = TestSocket.connectDevice(port, liveId, liveSecret)) {
            assertThat(frozen.status).isEqualTo(101);
            assertThat(frozen.readFrame().opcode()).isEqualTo(RawWebSocket.Frame.TEXT);
            live.next();
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> sessions.find(frozenId).isPresent()
                            && sessions.find(liveId).isPresent());
            WebSocketSession liveSession = sessions.find(liveId).orElseThrow();

            for (int ping = 1; ping <= 2; ping++) {
                clock.advance(PING_INTERVAL);
                keepalive.pingOrClose();
                assertThat(frozen.readFrame().opcode()).isEqualTo(RawWebSocket.Frame.PING);
                Instant pingedAt = clock.instant();
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> pingedAt.equals(SessionKeepalive.lastPong(liveSession)));
            }

            clock.advance(SessionKeepalive.PONG_TIMEOUT.minus(PING_INTERVAL.multipliedBy(2)));
            keepalive.pingOrClose();

            // The reason comes as a message before the close, which follows it.
            assertThat(frozen.readUntilText()).isEqualTo("{\"type\":\"closing\",\"reason\":\"timeout\"}");
            assertThat(frozen.readFrame().opcode()).isEqualTo(RawWebSocket.Frame.CLOSE);
            live.sync();
            assertThat(live.isOpen()).isTrue();
            assertThat(sessions.find(liveId)).containsSame(liveSession);
        }
    }

    @Test
    void observerThatNeverAnswersPingsIsClosedAfter45Seconds() throws Exception {
        clock.pin(NOW);
        String slug = TestData.uniqueSlug();
        data.device(slug, "Watched Panda");

        try (RawWebSocket frozen = RawWebSocket.handshake(
                port,
                "/ws/observe/" + slug,
                Map.of("Origin", TestSocket.PUBLIC_ORIGIN, "X-Forwarded-For", DeviceChannelTest.uniqueIp()))) {
            assertThat(frozen.status).isEqualTo(101);
            await().atMost(Duration.ofSeconds(5)).until(() -> observers.isWatched(slug));

            clock.advance(SessionKeepalive.PONG_TIMEOUT.minusSeconds(1));
            keepalive.pingOrClose();
            assertThat(frozen.readFrame().opcode()).isEqualTo(RawWebSocket.Frame.PING);

            clock.advance(Duration.ofSeconds(1));
            keepalive.pingOrClose();

            assertThat(frozen.readUntilCloseCode()).isEqualTo(1001);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> !observers.isWatched(slug));
    }

    @Test
    void shutdownTellsDevicesWhyAndClosesDeviceAndObserverSessionsWith1001() throws Exception {
        String slug = TestData.uniqueSlug();
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(slug, "Deployed Panda", secret);

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret);
                TestSocket observer = TestSocket.connectObserver(port, slug, DeviceChannelTest.uniqueIp())) {
            device.next();
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> sessions.find(deviceId).isPresent() && observers.isWatched(slug));

            keepalive.closeAllOnShutdown();

            assertThat(device.awaitClose().getCode()).isEqualTo(1001);
            assertThat(observer.awaitClose().getCode()).isEqualTo(1001);
            assertThat(device.nextJson()).isEqualTo(Map.of("type", "closing", "reason", "shutdown"));
            // Observers receive only reactions.
            assertThat(observer.pending()).isZero();
        }
    }
}
