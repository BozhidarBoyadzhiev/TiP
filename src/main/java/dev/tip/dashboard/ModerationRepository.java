package dev.tip.dashboard;

import static dev.tip.dashboard.DashboardRepository.utc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The owner's moderation of AI flagged messages. {@code tip.owner_verdict} and {@code tip.owner_verdict_at}
 * belong to this feature and are written only here; the AI's {@code flagged} and {@code flag_reason} are
 * never touched. Like every owner query, each statement joins the tip's device and keeps only the signed
 * in owner's tips in a visible status, and acts only on tips the AI flagged, so anything else is the same
 * empty answer.
 */
@Repository
class ModerationRepository {

    static final String NOT_ABUSIVE = "NOT_ABUSIVE";

    private static final String OWNED_FLAGGED_TIP = """
            FROM tip t
            JOIN device d ON d.id = t.device_id
            WHERE t.id = :tipId AND d.owner_id = :ownerId AND t.status IN (:visibleStatuses)
              AND\s""" + HiddenMessages.AI_FLAGGED;

    private final JdbcClient jdbc;

    ModerationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The message of one of the owner's AI flagged tips, hidden or not. */
    Optional<Message> message(UUID ownerId, UUID tipId) {
        return jdbc.sql("SELECT t.message " + OWNED_FLAGGED_TIP)
                .param("tipId", tipId)
                .param("ownerId", ownerId)
                .param("visibleStatuses", TipHistoryQuery.VISIBLE_STATUSES)
                .query((rs, row) -> new Message(rs.getString("message")))
                .optional();
    }

    /**
     * Sets the owner's verdict in one statement: the row is locked, then updated, so concurrent verdicts on
     * one tip run one after the other and each sees the verdict the one before it left. {@code
     * owner_verdict_at} moves only when the verdict changes, so repeating a verdict changes nothing. Empty
     * when the tip is not one of the owner's AI flagged tips.
     */
    Optional<Change> setHidden(UUID ownerId, UUID tipId, boolean hidden, Instant now) {
        return jdbc.sql("""
                        WITH target AS (
                            SELECT t.id, t.owner_verdict
                            """ + OWNED_FLAGGED_TIP + """

                            FOR UPDATE OF t
                        )
                        UPDATE tip SET owner_verdict = CAST(:verdict AS varchar),
                                       owner_verdict_at = CASE WHEN tip.owner_verdict IS NOT DISTINCT FROM
                                                                    CAST(:verdict AS varchar)
                                                               THEN tip.owner_verdict_at ELSE :now END
                        FROM target
                        WHERE tip.id = target.id
                        RETURNING target.owner_verdict AS previous""")
                .param("tipId", tipId)
                .param("ownerId", ownerId)
                .param("visibleStatuses", TipHistoryQuery.VISIBLE_STATUSES)
                .param("verdict", hidden ? null : NOT_ABUSIVE)
                .param("now", utc(now))
                .query((rs, row) -> new Change((rs.getString("previous") == null) != hidden))
                .optional();
    }

    /** The tipper's message as stored. */
    record Message(String text) {}

    /** {@code changed} is false when the tip already had the verdict asked for. */
    record Change(boolean changed) {}
}
