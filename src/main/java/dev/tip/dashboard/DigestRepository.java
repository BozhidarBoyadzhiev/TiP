package dev.tip.dashboard;

import static dev.tip.dashboard.DashboardRepository.instant;
import static dev.tip.dashboard.DashboardRepository.utc;

import dev.tip.dashboard.RequestsReport.StoredGroup;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code owner_digest} table. Every write is conditional, like the AI columns of {@code tip}: a claim
 * lets one job instance at a time generate an owner's digest, and a result is stored only when it read
 * newer reactions than the stored one, so a slow old call never overwrites a newer digest. Instants must
 * be truncated to microseconds, because the claim time is compared for equality.
 */
@Repository
class DigestRepository {

    private static final TypeReference<List<String>> BULLETS = new TypeReference<>() {};
    private static final TypeReference<List<StoredGroup>> GROUPS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    DigestRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Marks the owner's digest of {@code day} stale, creating its row on the day's first reaction. */
    void markStale(UUID ownerId, LocalDate day) {
        jdbc.sql("""
                        INSERT INTO owner_digest (owner_id, local_day, stale) VALUES (:ownerId, :day, true)
                        ON CONFLICT (owner_id, local_day) DO UPDATE SET stale = true""").param("ownerId", ownerId).param("day", day).update();
    }

    /** Stale digests of {@code earliest} or later. The job checks each against its owner's today. */
    List<Candidate> staleSince(LocalDate earliest) {
        return jdbc.sql("SELECT owner_id, local_day FROM owner_digest WHERE stale AND local_day >= :earliest")
                .param("earliest", earliest)
                .query((rs, row) ->
                        new Candidate(rs.getObject("owner_id", UUID.class), rs.getObject("local_day", LocalDate.class)))
                .list();
    }

    /**
     * Takes a stale digest unless another claim on it is still alive or the digest was generated after
     * {@code generatedBefore}. Clears the stale mark, so a reaction during generation marks it again.
     * Returns the stored watermark, null inside the claim when nothing was stored yet.
     */
    Optional<Claim> claim(UUID ownerId, LocalDate day, Instant now, Instant claimStaleBefore, Instant generatedBefore) {
        return jdbc.sql("""
                        UPDATE owner_digest SET claimed_at = :now, stale = false
                        WHERE owner_id = :ownerId AND local_day = :day AND stale
                          AND (claimed_at IS NULL OR claimed_at < :claimStaleBefore)
                          AND (generated_at IS NULL OR generated_at <= :generatedBefore)
                        RETURNING source_watermark""")
                .param("ownerId", ownerId)
                .param("day", day)
                .param("now", utc(now))
                .param("claimStaleBefore", utc(claimStaleBefore))
                .param("generatedBefore", utc(generatedBefore))
                .query((rs, row) -> new Claim(now, instant(rs, "source_watermark")))
                .optional();
    }

    /** Ends the claim, if it is still this one. {@code stale} true asks the next run to try again. */
    void release(UUID ownerId, LocalDate day, Instant claimedAt, boolean stale) {
        jdbc.sql("""
                        UPDATE owner_digest SET claimed_at = NULL, stale = stale OR :stale
                        WHERE owner_id = :ownerId AND local_day = :day AND claimed_at = :claimedAt""")
                .param("ownerId", ownerId)
                .param("day", day)
                .param("claimedAt", utc(claimedAt))
                .param("stale", stale)
                .update();
    }

    /** Stores a digest unless the stored one read reactions at least as new. True when it was stored. */
    boolean store(
            UUID ownerId,
            LocalDate day,
            List<String> bullets,
            List<StoredGroup> groups,
            int messageCount,
            Instant watermark,
            Instant generatedAt) {
        return jdbc.sql("""
                                UPDATE owner_digest
                                SET bullets = CAST(:bullets AS jsonb), request_groups = CAST(:groups AS jsonb),
                                    message_count = :messageCount, source_watermark = :watermark,
                                    generated_at = :generatedAt
                                WHERE owner_id = :ownerId AND local_day = :day
                                  AND (source_watermark IS NULL OR source_watermark < :watermark)""")
                        .param("ownerId", ownerId)
                        .param("day", day)
                        .param("bullets", json.writeValueAsString(bullets))
                        .param("groups", json.writeValueAsString(groups))
                        .param("messageCount", messageCount)
                        .param("watermark", utc(watermark))
                        .param("generatedAt", utc(generatedAt))
                        .update()
                == 1;
    }

    /** The stored digest of the day, empty when none was stored yet. */
    Optional<StoredDigest> find(UUID ownerId, LocalDate day) {
        return jdbc.sql("""
                        SELECT bullets::text AS bullets, request_groups::text AS request_groups, message_count,
                               generated_at
                        FROM owner_digest
                        WHERE owner_id = :ownerId AND local_day = :day AND bullets IS NOT NULL""")
                .param("ownerId", ownerId)
                .param("day", day)
                .query((rs, row) -> new StoredDigest(
                        json.readValue(rs.getString("bullets"), BULLETS),
                        rs.getString("request_groups") == null
                                ? List.of()
                                : json.readValue(rs.getString("request_groups"), GROUPS),
                        rs.getInt("message_count"),
                        instant(rs, "generated_at")))
                .optional();
    }

    record Candidate(UUID ownerId, LocalDate day) {}

    /** {@code storedWatermark} is null when no digest was stored for the day yet. */
    record Claim(Instant claimedAt, Instant storedWatermark) {}

    record StoredDigest(List<String> bullets, List<StoredGroup> groups, int messageCount, Instant generatedAt) {}
}
