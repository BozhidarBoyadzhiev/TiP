package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import dev.tip.ai.api.TipReacted;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class ReactionDeliveryTest extends IntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-13T14:02:11.123456Z");

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ReactionResendJob resendJob;

    @ParameterizedTest
    @EnumSource(Reaction.class)
    void reactionReachesTheConnectedDeviceInTheProtocolShape(Reaction reaction) throws Exception {
        clock.pin(T0);
        TestDevice device = device();
        UUID tipId = UUID.randomUUID();

        try (TestSocket socket = connect(device)) {
            socket.next();
            publish(tipId, device.id(), reaction, T0);

            assertThat(socket.nextJson())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "type",
                            "reaction",
                            "id",
                            tipId.toString(),
                            "reaction",
                            reaction.name(),
                            "at",
                            "2026-10-13T14:02:11Z"));
            awaitSentCount(tipId, 1);
            assertThat(row(tipId)).containsEntry("acked_at", null).containsEntry("last_sent_at", T0);
        }
    }

    @Test
    void ackStopsResends() throws Exception {
        clock.pin(T0);
        TestDevice device = device();
        UUID tipId = UUID.randomUUID();

        try (TestSocket socket = connect(device)) {
            socket.next();
            publish(tipId, device.id(), Reaction.PLAIN, T0);
            socket.next();
            awaitSentCount(tipId, 1);
            socket.ack(tipId);
            socket.sync();

            for (int second = 1; second <= 30; second++) {
                clock.advance(Duration.ofSeconds(1));
                resendJob.resend();
            }

            socket.sync();
            assertThat(socket.pending()).isZero();
            assertThat(row(tipId)).containsEntry("sent_count", 1);
            assertThat(row(tipId).get("acked_at")).isNotNull();
        }
    }

    @Test
    void withoutAnAckExactlyThreeResendsFiveSecondsApartThenNone() throws Exception {
        clock.pin(T0);
        TestDevice device = device();
        UUID tipId = UUID.randomUUID();

        try (TestSocket socket = connect(device)) {
            socket.next();
            publish(tipId, device.id(), Reaction.REQUEST, T0);
            socket.next();
            awaitSentCount(tipId, 1);

            List<Long> resentAtSecond = new ArrayList<>();
            for (int second = 1; second <= 40; second++) {
                clock.advance(Duration.ofSeconds(1));
                resendJob.resend();
                socket.sync();
                while (socket.pending() > 0) {
                    assertThat(socket.nextJson()).containsEntry("id", tipId.toString());
                    resentAtSecond.add((long) second);
                }
            }

            assertThat(resentAtSecond).containsExactly(5L, 10L, 15L);
            assertThat(row(tipId)).containsEntry("sent_count", 4);
        }
    }

    @Test
    void deviceAckingAnotherDevicesTipChangesNothing() throws Exception {
        clock.pin(T0);
        TestDevice owner = device();
        TestDevice other = device();
        UUID tipId = UUID.randomUUID();

        try (TestSocket ownerSocket = connect(owner);
                TestSocket otherSocket = connect(other)) {
            ownerSocket.next();
            otherSocket.next();
            publish(tipId, owner.id(), Reaction.WARM, T0);
            ownerSocket.next();
            awaitSentCount(tipId, 1);

            otherSocket.ack(tipId);
            otherSocket.sync();

            assertThat(row(tipId)).containsEntry("acked_at", null);
            clock.advance(ReactionSender.RESEND_INTERVAL);
            resendJob.resend();
            assertThat(ownerSocket.nextJson()).containsEntry("id", tipId.toString());
            assertThat(otherSocket.isOpen()).isTrue();
        }
    }

    @Test
    void ackForAnUnknownTipIsIgnored() throws Exception {
        TestDevice device = device();

        try (TestSocket socket = connect(device)) {
            socket.next();
            for (int i = 0; i < InvalidMessages.LIMIT + 5; i++) {
                socket.ack(UUID.randomUUID());
            }
            socket.sync();

            assertThat(socket.isOpen()).isTrue();
        }
    }

    @Test
    void reconnectReplaysOnlyUnackedReactionsNewerThan60SecondsOldestFirst() throws Exception {
        clock.pin(T0);
        TestDevice device = device();
        UUID tooOld = UUID.randomUUID();
        UUID acked = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        publish(newer, device.id(), Reaction.REQUEST, T0.minusSeconds(10));
        publish(tooOld, device.id(), Reaction.WARM, T0.minusSeconds(61));
        publish(older, device.id(), Reaction.PLAIN, T0.minusSeconds(40));
        publish(acked, device.id(), Reaction.WARM, T0.minusSeconds(50));
        jdbc.sql("UPDATE reaction_delivery SET acked_at = now() WHERE tip_id = ?")
                .param(acked)
                .update();

        try (TestSocket socket = connect(device)) {
            assertThat(socket.nextJson()).containsEntry("type", "hello");
            assertThat(socket.nextJson()).containsEntry("id", older.toString()).containsEntry("reaction", "PLAIN");
            assertThat(socket.nextJson()).containsEntry("id", newer.toString()).containsEntry("reaction", "REQUEST");
            socket.sync();
            assertThat(socket.pending()).isZero();
        }
        assertThat(row(older)).containsEntry("sent_count", 1);
        assertThat(row(newer)).containsEntry("sent_count", 1);
        assertThat(row(tooOld)).containsEntry("sent_count", 0);
        assertThat(row(acked)).containsEntry("sent_count", 0);
    }

    @Test
    void duplicateEventCreatesOneRowAndOneSend() throws Exception {
        clock.pin(T0);
        TestDevice device = device();
        UUID tipId = UUID.randomUUID();

        try (TestSocket socket = connect(device)) {
            socket.next();
            publish(tipId, device.id(), Reaction.WARM, T0);
            socket.next();
            awaitSentCount(tipId, 1);
            transactions.executeWithoutResult(
                    status -> events.publishEvent(new TipReacted(tipId, device.id(), Reaction.WARM, T0)));

            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> socket.pending() == 0);
        }
        assertThat(rows(tipId)).isOne();
        assertThat(row(tipId)).containsEntry("sent_count", 1);
    }

    @Test
    void fiftyReactionsFromTenThreadsAllArrive() throws Exception {
        TestDevice device = device();
        int threads = 10;
        int perThread = 5;
        List<UUID> tipIds = new ArrayList<>();
        for (int i = 0; i < threads * perThread; i++) {
            tipIds.add(UUID.randomUUID());
        }

        try (TestSocket socket = connect(device);
                ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            socket.next();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> publishers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                List<UUID> mine = tipIds.subList(t * perThread, (t + 1) * perThread);
                publishers.add(pool.submit(() -> {
                    start.await();
                    for (UUID tipId : mine) {
                        publish(tipId, device.id(), Reaction.WARM, clock.instant());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> publisher : publishers) {
                publisher.get(30, TimeUnit.SECONDS);
            }

            Set<String> received = new HashSet<>();
            for (int i = 0; i < tipIds.size(); i++) {
                received.add((String) socket.nextJson().get("id"));
            }
            assertThat(received)
                    .containsExactlyInAnyOrderElementsOf(
                            tipIds.stream().map(UUID::toString).toList());
            assertThat(socket.isOpen()).isTrue();
        }
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql(
                                "SELECT count(*) FROM reaction_delivery WHERE tip_id IN (:ids) AND sent_count = 1")
                        .param("ids", tipIds)
                        .query(Long.class)
                        .single()
                        .equals((long) tipIds.size()));
    }

    /** Publishes after commit, like the AI feature, and waits until the dispatcher stored the reaction. */
    private void publish(UUID tipId, UUID deviceId, Reaction reaction, Instant reactedAt) {
        transactions.executeWithoutResult(
                status -> events.publishEvent(new TipReacted(tipId, deviceId, reaction, reactedAt)));
        await().atMost(Duration.ofSeconds(10)).until(() -> rows(tipId) == 1);
    }

    private long rows(UUID tipId) {
        return jdbc.sql("SELECT count(*) FROM reaction_delivery WHERE tip_id = ?")
                .param(tipId)
                .query(Long.class)
                .single();
    }

    private void awaitSentCount(UUID tipId, int sentCount) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> row(tipId).get("sent_count").equals(sentCount));
    }

    private Map<String, Object> row(UUID tipId) {
        Map<String, Object> row = jdbc.sql("SELECT * FROM reaction_delivery WHERE tip_id = ?")
                .param(tipId)
                .query()
                .singleRow();
        row.computeIfPresent("last_sent_at", (k, v) -> ((Timestamp) v).toInstant());
        return row;
    }

    private TestDevice device() {
        String secret = TestData.randomSecret();
        return new TestDevice(data.device(TestData.uniqueSlug(), "Delivery Panda", secret), secret);
    }

    private record TestDevice(UUID id, String secret) {}

    /**
     * Returns once the server has finished the connect: it sends hello before it registers the session
     * and replays, so a reaction published right after hello could otherwise be sent twice.
     */
    private TestSocket connect(TestDevice device) {
        TestSocket socket = TestSocket.connectDevice(port, device.id(), device.secret());
        socket.sync();
        return socket;
    }
}
