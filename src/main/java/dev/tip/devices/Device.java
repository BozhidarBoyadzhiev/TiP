package dev.tip.devices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device")
class Device {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** SHA-256 of the device secret's 32 decoded bytes. */
    @Column(name = "secret_hash", nullable = false)
    private byte[] secretHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Written only by {@link DeviceRepository#markSeen}, see {@link DevicePresenceTracker}. */
    @Column(name = "last_seen_at", insertable = false, updatable = false)
    private Instant lastSeenAt;

    protected Device() {}

    Device(UUID id, String slug, String displayName, UUID ownerId, byte[] secretHash, Instant createdAt) {
        this.id = id;
        this.slug = slug;
        this.displayName = displayName;
        this.ownerId = ownerId;
        this.secretHash = secretHash.clone();
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }

    String slug() {
        return slug;
    }

    String displayName() {
        return displayName;
    }

    UUID ownerId() {
        return ownerId;
    }

    Instant lastSeenAt() {
        return lastSeenAt;
    }

    byte[] secretHash() {
        return secretHash.clone();
    }

    void assignTo(UUID ownerId) {
        this.ownerId = ownerId;
    }

    void replaceSecretHash(byte[] secretHash) {
        this.secretHash = secretHash.clone();
    }
}
