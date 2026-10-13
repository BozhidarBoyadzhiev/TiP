package dev.tip.owners;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code resume_token} table: only the SHA-256 of each token. A token is consumed by one conditional UPDATE,
 * so of two browsers opening the same link at once exactly one gets the session.
 */
@Repository
class ResumeTokens {

    private final JdbcClient jdbc;

    ResumeTokens(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void store(byte[] tokenHash, UUID ownerId, Instant createdAt, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO resume_token (token_hash, owner_id, created_at, expires_at)
                        VALUES (:hash, :ownerId, :createdAt, :expiresAt)""")
                .param("hash", tokenHash)
                .param("ownerId", ownerId)
                .param("createdAt", utc(createdAt))
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    /** The token's owner when it was unused and unexpired; it is used from now on. Empty otherwise. */
    Optional<UUID> consume(byte[] tokenHash, Instant now) {
        return jdbc.sql("""
                        UPDATE resume_token SET used_at = :now
                        WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now
                        RETURNING owner_id""")
                .param("hash", tokenHash)
                .param("now", utc(now))
                .query(UUID.class)
                .optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
