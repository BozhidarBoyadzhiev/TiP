package dev.tip.owners;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@Entity
@Table(name = "owner")
class Owner {

    /** Also the column's default. */
    static final String DEFAULT_TIME_ZONE = "Europe/London";

    @Id
    private UUID id;

    /** Always lowercase, see {@link OwnerEmails}. */
    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * An IANA zone id, read with {@link ZoneId}. Nothing sets it but the column default yet; whatever sets it
     * later must check it with {@code ZoneId.of} first.
     */
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    protected Owner() {}

    Owner(UUID id, String email, String passwordHash, String displayName, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.createdAt = createdAt;
        this.timeZone = DEFAULT_TIME_ZONE;
    }

    UUID id() {
        return id;
    }

    String email() {
        return email;
    }

    String passwordHash() {
        return passwordHash;
    }

    String displayName() {
        return displayName;
    }

    ZoneId timeZone() {
        return ZoneId.of(timeZone);
    }
}
