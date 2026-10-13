package dev.tip.owners;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every hour, deletes expired sandbox owners in batches of {@link #BATCH_SIZE}, each batch its own transaction. The
 * foreign keys cascade from the owner to its devices, their tips and deliveries, its digests and resume tokens (see
 * {@code V8__access_levels.sql}). Their HTTP sessions already end on their next request ({@link OwnerAccessFilter})
 * or at their idle timeout, which never reaches past the expiry. A webhook or capture that arrives later finds no
 * tip and is acknowledged. {@code SKIP LOCKED} lets two instances share the work. One INFO {@code sandbox_expired}
 * line per sandbox.
 */
@Component
class SandboxCleanupJob {

    static final int BATCH_SIZE = 20;

    private static final Logger log = LoggerFactory.getLogger(SandboxCleanupJob.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    SandboxCleanupJob(JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tip.owners.sandbox-cleanup-interval}",
            initialDelayString = "${tip.owners.sandbox-cleanup-interval}")
    void deleteExpired() {
        Instant now = clock.instant();
        int deleted;
        do {
            List<Map<String, Object>> batch = transactions.execute(status -> deleteBatch(now));
            batch.forEach(row -> log.info("sandbox_expired owner={} label={}", row.get("id"), row.get("code_label")));
            deleted = batch.size();
        } while (deleted == BATCH_SIZE);
    }

    private List<Map<String, Object>> deleteBatch(Instant now) {
        return jdbc.sql("""
                        DELETE FROM owner
                        WHERE id IN (SELECT id FROM owner
                                     WHERE kind = 'SANDBOX' AND expires_at <= :now
                                     ORDER BY expires_at
                                     LIMIT :limit
                                     FOR UPDATE SKIP LOCKED)
                        RETURNING id, code_label""")
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("limit", BATCH_SIZE)
                .query()
                .listOfRows();
    }
}
