package dev.tip.tipping;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the small database from growing until the end of 2026: every night ({@code tip.tipping.retention-cron},
 * 00:20 Europe/London) it deletes webhook events and unpaid tips, {@code ABANDONED} or {@code FAILED}, older than
 * {@link #KEEP}, {@link #BATCH} rows per statement. PayPal retries a webhook for three days at most, so an event that
 * old can no longer arrive again, and neither can a capture that would revive such a tip. Unpaid tips never reached
 * the owner, a device or the model.
 */
@Component
class RetentionJob {

    static final Duration KEEP = Duration.ofDays(30);

    static final int BATCH = 1000;

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    RetentionJob(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${tip.tipping.retention-cron}", zone = "Europe/London")
    void deleteOldRows() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(KEEP));
        int events = untilNoneLeft("""
                DELETE FROM paypal_webhook_event WHERE event_id IN (
                    SELECT event_id FROM paypal_webhook_event WHERE received_at < :cutoff LIMIT :batch)""", cutoff);
        int tips = untilNoneLeft("""
                DELETE FROM tip WHERE id IN (
                    SELECT id FROM tip WHERE status IN ('ABANDONED', 'FAILED') AND created_at < :cutoff
                    LIMIT :batch)""", cutoff);
        log.info("retention webhookEvents={} unpaidTips={} before={}", events, tips, cutoff.toInstant());
    }

    /** Each statement is its own transaction, so no lock is held across batches. */
    private int untilNoneLeft(String sql, Timestamp cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted =
                    jdbc.sql(sql).param("cutoff", cutoff).param("batch", BATCH).update();
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
