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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Device() {}

    Device(UUID id, String slug, String displayName, Instant createdAt) {
        this.id = id;
        this.slug = slug;
        this.displayName = displayName;
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
}
