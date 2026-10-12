package dev.tip.ai;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The AI columns of the {@code tip} table ({@code message_kind}, {@code reaction}, {@code flagged},
 * {@code flag_reason}, {@code owner_note}, {@code message_language}, {@code message_english}, {@code
 * requested_song}, {@code requested_song_key}, {@code ai_*}, {@code reaction_at}) belong to this feature. Every write is conditional, so PostgreSQL
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
                        RETURNING device_id, message, ai_attempts, amount_minor, currency""")
                .param("id", tipId)
                .param("now", utc(now))
                .param("staleBefore", utc(staleBefore))
                .param("maxAttempts", TipReactionWorker.MAX_ATTEMPTS)
                .query((rs, row) -> new Claim(
                        rs.getObject("device_id", UUID.class),
                        rs.getString("message"),
                        rs.getInt("ai_attempts"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency")))
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
    int finish(UUID tipId, Instant claimedAt, TipReactions.Outcome outcome, Instant reactedAt) {
        return jdbc.sql("""
                        UPDATE tip SET message_kind = :kind, reaction = :reaction, flagged = :flagged,
                                       flag_reason = :flagReason, owner_note = :ownerNote,
                                       message_language = :language, message_english = :englishText,
                                       requested_song = :song, requested_song_key = :songKey,
                                       ai_status = :status, reaction_at = :reactedAt
                        WHERE id = :id AND ai_status = 'PROCESSING' AND ai_claimed_at = :claimedAt""")
                .param("id", tipId)
                .param("claimedAt", utc(claimedAt))
                .param("kind", outcome.kind() == null ? null : outcome.kind().name())
                .param("reaction", outcome.reaction().name())
                .param("flagged", outcome.flagged())
                .param("flagReason", outcome.flagReason())
                .param("ownerNote", outcome.ownerNote())
                .param("language", outcome.language())
                .param("englishText", outcome.englishText())
                .param("song", outcome.song())
                .param("songKey", outcome.songKey())
                .param("status", outcome.status().name())
                .param("reactedAt", utc(reactedAt))
                .update();
    }

    /**
     * A worker that crashed during the last allowed attempt leaves the tip PROCESSING with no attempts
     * left. Once that claim expires, the tip fails as PLAIN so the panda still reacts. Returns the
     * tip's device and amount when this call made the change.
     */
    Optional<Exhausted> failExhausted(UUID tipId, Instant staleBefore, Instant reactedAt) {
        return jdbc.sql("""
                        UPDATE tip SET message_kind = NULL, reaction = 'PLAIN', flagged = false,
                                       flag_reason = NULL, owner_note = NULL, message_language = NULL,
                                       message_english = NULL, requested_song = NULL, requested_song_key = NULL,
                                       ai_status = 'FAILED', reaction_at = :reactedAt
                        WHERE id = :id AND status = 'CAPTURED' AND ai_status = 'PROCESSING'
                          AND ai_claimed_at < :staleBefore AND ai_attempts >= :maxAttempts
                        RETURNING device_id, amount_minor, currency""")
                .param("id", tipId)
                .param("staleBefore", utc(staleBefore))
                .param("reactedAt", utc(reactedAt))
                .param("maxAttempts", TipReactionWorker.MAX_ATTEMPTS)
                .query((rs, row) -> new Exhausted(
                        rs.getObject("device_id", UUID.class), rs.getLong("amount_minor"), rs.getString("currency")))
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

    /** {@code amountMinor} is the amount the server created the PayPal order for, which is what was charged. */
    record Claim(UUID deviceId, String message, int attempts, long amountMinor, String currency) {}

    record Exhausted(UUID deviceId, long amountMinor, String currency) {}
}
