package dev.tip.owners;

import dev.tip.owners.api.OwnerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** Always lowercase, see {@link OwnerEmails}. Null for a sandbox and the public demo. */
    @Column(unique = true, length = 254)
    private String email;

    /** Null for a sandbox and the public demo, which never sign in with a password. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** An IANA zone id, read with {@link ZoneId}. Whatever sets it must check it with {@code ZoneId.of} first. */
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private OwnerKind kind;

    /** The label of the access code a sandbox was made with. */
    @Column(name = "code_label", length = 32, updatable = false)
    private String codeLabel;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    protected Owner() {}

    Owner(UUID id, String email, String passwordHash, String displayName, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.createdAt = createdAt;
        this.timeZone = DEFAULT_TIME_ZONE;
        this.kind = OwnerKind.REAL;
    }

    /** A sandbox owner: no email, no password, valid until {@code expiresAt}. */
    static Owner sandbox(
            UUID id, String displayName, ZoneId timeZone, String codeLabel, Instant createdAt, Instant expiresAt) {
        Owner owner = new Owner(id, null, null, displayName, createdAt);
        owner.timeZone = timeZone.getId();
        owner.kind = OwnerKind.SANDBOX;
        owner.codeLabel = codeLabel;
        owner.expiresAt = expiresAt;
        return owner;
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

    OwnerKind kind() {
        return kind;
    }

    String codeLabel() {
        return codeLabel;
    }

    Instant expiresAt() {
        return expiresAt;
    }
}
