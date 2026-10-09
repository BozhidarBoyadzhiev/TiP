package dev.tip.ai;

import dev.tip.ai.api.Reaction;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The AI columns of the {@code tip} table ({@code reaction}, {@code flagged}, {@code flag_reason},
 * {@code ai_*}, {@code reaction_at}) belong to this feature. Every write is conditional, so PostgreSQL
 * row locking decides which worker owns a tip and whose result counts. Instants must already be
 * truncated to microseconds, the precision of {@code timestamptz}, because the claim time is compared
 * for equality.
 */
@Repository
class AiTipRepository {

    private final JdbcClient jdbc;

    AiTipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes a pending tip, or one whose claim expired, unless it used up its attempts. */
    Optional<Claim> claim(UUID tipId, Instant now, Instant staleBefore) {
        return jdbc.sql("""
                        UPDATE tip SET ai_status = 'PROCESSING', ai_claimed_at = :now, ai_attempts = ai_attempts + 1
                        WHERE id = :id AND status = 'CAPTURED'
                          AND (ai_status = 'PENDING' OR (ai_status = 'PROCESSING' AND ai_claimed_at < :staleBefore))
                          AND ai_attempts < :maxAttempts
                        RETURNING device_id, message, ai_attempts""")
                .param("id", tipId)
                .param("now", utc(now))
                .param("staleBefore", utc(staleBefore))
                .param("maxAttempts", TipReactionWorker.MAX_ATTEMPTS)
                .query((rs, row) -> new Claim(
                        rs.getObject("device_id", UUID.class), rs.getString("message"), rs.getInt("ai_attempts")))
                .optional();
    }

    /** Hands a failed attempt back to the recovery job. Only the current claim holder can. */
    int release(UUID tipId, Instant claimedAt) {
        return jdbc.sql("""
                        UPDATE tip SET ai_status = 'PENDING'
                        WHERE id = :id AND ai_status = 'PROCESSING' AND ai_claimed_at = :claimedAt""")
                .param("id", tipId)
                .param("claimedAt", utc(claimedAt))
                .update();
    }

    /** Stores the final result. Rejected when the claim expired and someone else took the tip over. */
    int finish(
            UUID tipId,
            Instant claimedAt,
            Reaction reaction,
            boolean flagged,
            String flagReason,
            AiStatus status,
            Instant reactedAt) {
        return jdbc.sql("""
                        UPDATE tip SET reaction = :reaction, flagged = :flagged, flag_reason = :flagReason,
                                       ai_status = :status, reaction_at = :reactedAt
                        WHERE id = :id AND ai_status = 'PROCESSING' AND ai_claimed_at = :claimedAt""")
                .param("id", tipId)
                .param("claimedAt", utc(claimedAt))
                .param("reaction", reaction.name())
                .param("flagged", flagged)
                .param("flagReason", flagReason)
                .param("status", status.name())
                .param("reactedAt", utc(reactedAt))
                .update();
    }

    /**
     * A worker that crashed during the last allowed attempt leaves the tip PROCESSING with no attempts
     * left. Once that claim expires, the tip fails as NEUTRAL so the panda still reacts. Returns the
     * device id when this call made the change.
     */
    Optional<UUID> failExhausted(UUID tipId, Instant staleBefore, Instant reactedAt) {
        return jdbc.sql("""
                        UPDATE tip SET reaction = 'NEUTRAL', flagged = false, flag_reason = NULL,
                                       ai_status = 'FAILED', reaction_at = :reactedAt
                        WHERE id = :id AND status = 'CAPTURED' AND ai_status = 'PROCESSING'
                          AND ai_claimed_at < :staleBefore AND ai_attempts >= :maxAttempts
                        RETURNING device_id""")
                .param("id", tipId)
                .param("staleBefore", utc(staleBefore))
                .param("reactedAt", utc(reactedAt))
                .param("maxAttempts", TipReactionWorker.MAX_ATTEMPTS)
                .query((rs, row) -> rs.getObject("device_id", UUID.class))
                .optional();
    }

    /** Captured tips the flow has not finished: pending, or processing under an expired claim. Oldest first. */
    List<UUID> findUnfinished(Instant staleBefore, int limit) {
        return jdbc.sql("""
                        SELECT id FROM tip
                        WHERE status = 'CAPTURED'
                          AND (ai_status = 'PENDING' OR (ai_status = 'PROCESSING' AND ai_claimed_at < :staleBefore))
                        ORDER BY captured_at
                        LIMIT :limit""")
                .param("staleBefore", utc(staleBefore))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record Claim(UUID deviceId, String message, int attempts) {}
}
