package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import dev.tip.ai.api.TipReacted;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * A reaction stored while the device's session is still pending (registered, before {@code hello}) is not
 * sent live; it arrives after {@code hello}, from the connect replay, exactly once. The test runs the
 * dispatcher inside the pending window itself, from the presence tracker's connect call, so the order is
 * set by the test, never by timing.
 */
class DeviceReadyGateTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-13T14:00:00Z");

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    DeviceSessions sessions;

    @Autowired
    ReactionDispatcher dispatcher;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    DevicePresenceTracker presence;

    @Test
    void reactionStoredWhilePendingArrivesAfterHelloExactlyOnce() throws Exception {
        clock.pin(NOW);
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Gate Panda", secret);
        UUID tipId = UUID.randomUUID();
        Map<String, Object> window = new HashMap<>();
        doAnswer(invocation -> {
                    if (deviceId.equals(invocation.getArgument(0))) {
                        window.put("registered", sessions.registered(deviceId).isPresent());
                        window.put("ready", sessions.find(deviceId).isPresent());
                        ReactionDispatcher direct = AopTestUtils.getUltimateTargetObject(dispatcher);
                        direct.on(new TipReacted(tipId, deviceId, Reaction.PLAYFUL, 3, NOW));
                        window.put("sentWhilePending", sentCount(tipId));
                    }
                    return invocation.callRealMethod();
                })
                .when(presence)
                .connected(any());

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret)) {
            assertThat(device.nextJson()).containsEntry("type", "hello");
            assertThat(device.nextJson())
                    .containsEntry("type", "reaction")
                    .containsEntry("id", tipId.toString())
                    .containsEntry("reaction", "PLAYFUL")
                    .containsEntry("repeats", 3);
            device.sync();
            assertThat(device.pending()).isZero();
        }

        assertThat(window).containsEntry("registered", true).containsEntry("ready", false);
        assertThat(window).containsEntry("sentWhilePending", 0);
        assertThat(sentCount(tipId)).isOne();
    }

    private int sentCount(UUID tipId) {
        return jdbc.sql("SELECT sent_count FROM reaction_delivery WHERE tip_id = ?")
                .param(tipId)
                .query(Integer.class)
                .single();
    }
}
