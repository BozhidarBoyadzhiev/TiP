package dev.tip.dashboard;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read only queries on the owner's tips for the today and requests panels and the digest, like {@link
 * TipHistoryRepository}: every query joins the tip's device and keeps only the signed in owner's rows, and
 * nothing here writes to {@code tip} or {@code device}. Flagged tips never leave the database except as a
 * count, and their message, note, language and song never at all.
 */
@Repository
class DashboardRepository {

    private static final String OWNED_VISIBLE_TIPS = """
            FROM tip t
            JOIN device d ON d.id = t.device_id
            WHERE d.owner_id = :ownerId
              AND t.status IN ('CAPTURED', 'PENDING', 'REFUNDED', 'REVERSED')
            """;

    /** Classified unflagged requests. Only a classified tip is known not to be flagged. */
    private static final String UNFLAGGED_REQUESTS =
            " AND t.ai_status = 'DONE' AND NOT t.flagged AND t.message_kind = 'REQUEST'";

    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The owner of a device, empty for an unknown device. */
    Optional<UUID> ownerOfDevice(UUID deviceId) {
        return jdbc.sql("SELECT owner_id FROM device WHERE id = :id")
                .param("id", deviceId)
                .query(UUID.class)
                .optional();
    }

    /** The owner's tips created in {@code [from, to]}, without any text. */
    List<TipFact> tipFacts(UUID ownerId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT t.created_at, t.amount_minor, t.currency, t.status,
                               (t.ai_status = 'DONE' AND NOT t.flagged AND t.message_kind = 'REQUEST') AS request
                        """ + OWNED_VISIBLE_TIPS + " AND t.created_at >= :from AND t.created_at <= :to")
                .param("ownerId", ownerId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((rs, row) -> new TipFact(
                        instant(rs, "created_at"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getBoolean("request")))
                .list();
    }

    /** The owner's unflagged requests created in {@code [from, to]}, newest first. */
    List<RequestRow> requests(UUID ownerId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT t.id, t.created_at, t.owner_note, t.requested_song, t.requested_song_key
                        """ + OWNED_VISIBLE_TIPS + UNFLAGGED_REQUESTS
                        + " AND t.created_at >= :from AND t.created_at <= :to ORDER BY t.created_at DESC, t.id DESC")
                .param("ownerId", ownerId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((rs, row) -> new RequestRow(
                        rs.getObject("id", UUID.class),
                        instant(rs, "created_at"),
                        rs.getString("owner_note"),
                        rs.getString("requested_song"),
                        rs.getString("requested_song_key")))
                .list();
    }

    /** The newest {@code limit} unflagged classified messages created in {@code [from, to]}. */
    List<DigestInput> digestMessages(UUID ownerId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT t.id, t.message AS text, t.reaction_at
                        """ + OWNED_VISIBLE_TIPS
                        + " AND t.ai_status = 'DONE' AND NOT t.flagged AND t.message IS NOT NULL"
                        + " AND t.created_at >= :from AND t.created_at <= :to"
                        + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit")
                .param("ownerId", ownerId)
                .param("from", utc(from))
                .param("to", utc(to))
                .param("limit", limit)
                .query(DashboardRepository::digestInput)
                .list();
    }

    /** The newest {@code limit} owner notes of unflagged requests created in {@code [from, to]}. */
    List<DigestInput> digestNotes(UUID ownerId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT t.id, t.owner_note AS text, t.reaction_at
                        """ + OWNED_VISIBLE_TIPS + UNFLAGGED_REQUESTS
                        + " AND t.owner_note IS NOT NULL AND t.created_at >= :from AND t.created_at <= :to"
                        + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit")
                .param("ownerId", ownerId)
                .param("from", utc(from))
                .param("to", utc(to))
                .param("limit", limit)
                .query(DashboardRepository::digestInput)
                .list();
    }

    private static DigestInput digestInput(ResultSet rs, int row) throws SQLException {
        return new DigestInput(rs.getObject("id", UUID.class), rs.getString("text"), instant(rs, "reaction_at"));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** {@code request} is true for a classified, unflagged request. */
    record TipFact(Instant createdAt, long amountMinor, String currency, String status, boolean request) {}

    record RequestRow(UUID tipId, Instant createdAt, String ownerNote, String song, String songKey) {}

    /** A message or a note for the digest, with the time its tip was classified. */
    record DigestInput(UUID tipId, String text, Instant reactedAt) {}
}
