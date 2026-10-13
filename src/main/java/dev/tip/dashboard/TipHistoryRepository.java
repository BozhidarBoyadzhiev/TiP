package dev.tip.dashboard;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads tips for their owner. The {@code tip} and {@code device} tables belong to other features, so
 * this reads only the columns it shows with its own SQL and never writes. Every query joins the tip's
 * device and keeps only rows whose {@code owner_id} is the signed in owner, so another owner's tip never
 * leaves the database. Filters are fixed SQL fragments with named parameters; no request text becomes
 * SQL.
 */
@Repository
class TipHistoryRepository {

    /** The texts of a hidden message stay in the database, see {@link HiddenMessages}. */
    private static final String SELECT = "SELECT t.id, t.created_at, t.amount_minor, t.currency, t.status,"
            + " d.slug AS device_slug, t.message_kind, t.reaction, rd.repeats, t.ai_status, t.demo,"
            + " " + HiddenMessages.HIDDEN + " AS hidden,"
            + " " + HiddenMessages.UNHIDDEN + " AS unhidden,"
            + " CASE WHEN " + HiddenMessages.AI_FLAGGED + " THEN t.flag_reason END AS flag_reason,"
            + " " + HiddenMessages.unlessHidden("t.message") + " AS message,"
            + " " + HiddenMessages.unlessHidden("t.owner_note") + " AS owner_note,"
            + " " + HiddenMessages.unlessHidden("t.message_language") + " AS message_language,"
            + " " + HiddenMessages.unlessHidden("t.message_english") + " AS message_english,"
            + " " + HiddenMessages.unlessHidden("t.requested_song") + " AS requested_song\n";

    private static final String OWNED_VISIBLE_TIPS = """
            FROM tip t
            JOIN device d ON d.id = t.device_id
            LEFT JOIN reaction_delivery rd ON rd.tip_id = t.id
            WHERE d.owner_id = :ownerId
              AND t.status IN (:visibleStatuses)
            """;

    private final JdbcClient jdbc;

    TipHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One query for the page and one for the total, whatever the page size. Repeatable read gives both
     * the same snapshot, so a tip captured in between cannot make the total disagree with the items.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    TipPage page(UUID ownerId, TipHistoryQuery query) {
        Filter filter = Filter.of(ownerId, query);
        List<TipView> items = jdbc.sql(
                        SELECT + filter.sql() + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit OFFSET :offset")
                .params(filter.params())
                .param("limit", query.size())
                .param("offset", query.offset())
                .query(TipHistoryRepository::tipView)
                .list();
        long total = jdbc.sql("SELECT count(*) " + filter.sql())
                .params(filter.params())
                .query(Long.class)
                .single();
        return new TipPage(items, query.page(), query.size(), total);
    }

    /** Empty for an unknown id, another owner's tip and a tip in a hidden status alike. */
    Optional<TipView> find(UUID ownerId, UUID tipId) {
        return jdbc.sql(SELECT + OWNED_VISIBLE_TIPS + " AND t.id = :tipId")
                .param("ownerId", ownerId)
                .param("visibleStatuses", TipHistoryQuery.VISIBLE_STATUSES)
                .param("tipId", tipId)
                .query(TipHistoryRepository::tipView)
                .optional();
    }

    private static TipView tipView(ResultSet rs, int row) throws SQLException {
        return new TipView(
                rs.getObject("id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getString("device_slug"),
                rs.getString("message"),
                rs.getString("message_kind"),
                rs.getString("reaction"),
                rs.getObject("repeats", Integer.class),
                rs.getBoolean("hidden"),
                rs.getBoolean("unhidden"),
                rs.getString("flag_reason"),
                rs.getString("owner_note"),
                rs.getString("message_language"),
                Languages.englishName(rs.getString("message_language")),
                rs.getString("message_english"),
                rs.getString("requested_song"),
                rs.getString("ai_status"),
                rs.getBoolean("demo"));
    }

    /** The {@code FROM ... WHERE} part shared by the page and the count, with its parameters. */
    private record Filter(String sql, Map<String, Object> params) {

        static Filter of(UUID ownerId, TipHistoryQuery query) {
            StringBuilder sql = new StringBuilder(OWNED_VISIBLE_TIPS);
            Map<String, Object> params = new HashMap<>();
            params.put("ownerId", ownerId);
            params.put("visibleStatuses", TipHistoryQuery.VISIBLE_STATUSES);
            if (query.status() != null) {
                sql.append(" AND t.status = :status");
                params.put("status", query.status());
            }
            if (query.kind() != null) {
                sql.append(" AND t.message_kind = :kind");
                params.put("kind", query.kind());
            }
            if (query.flagged() != null) {
                sql.append(query.flagged() ? " AND " + HiddenMessages.HIDDEN : " AND " + HiddenMessages.SHOWN);
            }
            if (query.from() != null) {
                sql.append(" AND t.created_at >= :fromInclusive");
                params.put("fromInclusive", startOfDay(query.from()));
            }
            if (query.to() != null) {
                sql.append(" AND t.created_at < :toExclusive");
                params.put("toExclusive", startOfDay(query.to().plusDays(1)));
            }
            return new Filter(sql.toString(), Map.copyOf(params));
        }

        /** Midnight UTC, because the date filters are UTC days. */
        private static OffsetDateTime startOfDay(LocalDate date) {
            return date.atStartOfDay().atOffset(ZoneOffset.UTC);
        }
    }
}
