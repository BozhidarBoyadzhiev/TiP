package dev.tip.devices;

import dev.tip.ai.api.Reaction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code reaction_delivery} table. Every method runs in a transaction of its own: the dispatcher
 * calls them from an after commit listener, where the finished transaction's connection is still bound
 * to the thread and would never commit again.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
class ReactionDeliveryRepository {

    private final JdbcClient jdbc;

    ReactionDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a new reaction. False when the tip already has one, so the event was a duplicate. */
    boolean insert(PendingReaction reaction) {
        return jdbc.sql("""
                        INSERT INTO reaction_delivery (tip_id, device_id, reaction, repeats, reacted_at)
                        VALUES (:tipId, :deviceId, :reaction, :repeats, :reactedAt)
                        ON CONFLICT (tip_id) DO NOTHING""")
                        .param("tipId", reaction.tipId())
                        .param("deviceId", reaction.deviceId())
                        .param("reaction", reaction.reaction().name())
                        .param("repeats", reaction.repeats())
                        .param("reactedAt", utc(reaction.reactedAt()))
                        .update()
                == 1;
    }

    void recordSend(UUID tipId, Instant sentAt) {
        jdbc.sql("""
                        UPDATE reaction_delivery SET sent_count = sent_count + 1, last_sent_at = :sentAt
                        WHERE tip_id = :tipId""").param("tipId", tipId).param("sentAt", utc(sentAt)).update();
    }

    /** Marks the reaction acknowledged. Only the tip's own device can, and only once. */
    boolean ack(UUID tipId, UUID deviceId, Instant ackedAt) {
        return jdbc.sql("""
                        UPDATE reaction_delivery SET acked_at = :ackedAt
                        WHERE tip_id = :tipId AND device_id = :deviceId AND acked_at IS NULL""")
                        .param("tipId", tipId)
                        .param("deviceId", deviceId)
                        .param("ackedAt", utc(ackedAt))
                        .update()
                == 1;
    }

    /** Unacknowledged reactions of the device newer than {@code since}, oldest first. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    List<PendingReaction> unacked(UUID deviceId, Instant since) {
        return jdbc.sql("""
                        SELECT tip_id, device_id, reaction, repeats, reacted_at FROM reaction_delivery
                        WHERE device_id = :deviceId AND acked_at IS NULL AND reacted_at > :since
                        ORDER BY reacted_at, tip_id""")
                .param("deviceId", deviceId)
                .param("since", utc(since))
                .query(ReactionDeliveryRepository::pending)
                .list();
    }

    /**
     * Unacknowledged reactions of the given devices that are due again: sent fewer than {@code maxSends}
     * times, last sent at least {@code interval} ago, and newer than {@code since}. Oldest first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    List<PendingReaction> dueForResend(
            List<UUID> deviceIds, Instant now, Duration interval, int maxSends, Instant since) {
        return jdbc.sql("""
                        SELECT tip_id, device_id, reaction, repeats, reacted_at FROM reaction_delivery
                        WHERE device_id IN (:deviceIds) AND acked_at IS NULL
                          AND sent_count < :maxSends AND last_sent_at <= :sentBefore AND reacted_at > :since
                        ORDER BY reacted_at, tip_id""")
                .param("deviceIds", deviceIds)
                .param("maxSends", maxSends)
                .param("sentBefore", utc(now.minus(interval)))
                .param("since", utc(since))
                .query(ReactionDeliveryRepository::pending)
                .list();
    }

    private static PendingReaction pending(ResultSet rs, int row) throws SQLException {
        return new PendingReaction(
                rs.getObject("tip_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                Reaction.valueOf(rs.getString("reaction")),
                rs.getInt("repeats"),
                rs.getObject("reacted_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
