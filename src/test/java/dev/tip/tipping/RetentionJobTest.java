package dev.tip.tipping;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class RetentionJobTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-20T00:20:00Z");

    @Autowired
    RetentionJob job;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    @Test
    void unpaidTipsAndWebhookEventsOlderThanThirtyDaysAreDeleted() {
        clock.pin(NOW);
        Instant old = NOW.minus(RetentionJob.KEEP).minusSeconds(1);
        Instant recent = NOW.minus(RetentionJob.KEEP).plusSeconds(60);
        UUID deviceId = data.device();
        UUID oldAbandoned = data.tip(deviceId, "ABANDONED", null, old);
        UUID oldFailed = data.tip(deviceId, "FAILED", null, old);
        UUID recentAbandoned = data.tip(deviceId, "ABANDONED", null, recent);
        UUID oldCreated = data.tip(deviceId, "CREATED", null, old);
        UUID oldCaptured = data.tip(deviceId, "CAPTURED", null, old);
        UUID oldRefunded = data.tip(deviceId, "REFUNDED", null, old);
        String oldEvent = webhookEvent(old);
        String recentEvent = webhookEvent(recent);

        job.deleteOldRows();

        assertThat(exists(oldAbandoned)).isFalse();
        assertThat(exists(oldFailed)).isFalse();
        assertThat(exists(recentAbandoned)).isTrue();
        assertThat(exists(oldCreated)).isTrue();
        assertThat(exists(oldCaptured)).isTrue();
        assertThat(exists(oldRefunded)).isTrue();
        assertThat(data.webhookEvents(oldEvent)).isZero();
        assertThat(data.webhookEvents(recentEvent)).isOne();
    }

    @Test
    void moreRowsThanOneBatchAreAllDeleted() {
        clock.pin(NOW.plus(Duration.ofDays(1)));
        Instant old = NOW.minus(RetentionJob.KEEP).minus(Duration.ofDays(1));
        jdbc.sql("""
                        INSERT INTO paypal_webhook_event (event_id, event_type, resource_id, received_at)
                        SELECT 'WH-BATCH-' || n || '-' || :suffix, 'PAYMENT.CAPTURE.COMPLETED', 'R', :at
                        FROM generate_series(1, :count) AS n""")
                .param("suffix", UUID.randomUUID().toString())
                .param("at", Timestamp.from(old))
                .param("count", RetentionJob.BATCH + 5)
                .update();

        job.deleteOldRows();

        assertThat(jdbc.sql("SELECT count(*) FROM paypal_webhook_event WHERE received_at < ?")
                        .param(Timestamp.from(NOW.minus(RetentionJob.KEEP)))
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private String webhookEvent(Instant receivedAt) {
        String eventId = TestData.uniqueId("WH");
        jdbc.sql("INSERT INTO paypal_webhook_event (event_id, event_type, resource_id, received_at)"
                        + " VALUES (?, 'PAYMENT.CAPTURE.COMPLETED', 'R', ?)")
                .params(eventId, Timestamp.from(receivedAt))
                .update();
        return eventId;
    }

    private boolean exists(UUID tipId) {
        return jdbc.sql("SELECT count(*) FROM tip WHERE id = ?")
                        .param(tipId)
                        .query(Long.class)
                        .single()
                == 1;
    }
}
