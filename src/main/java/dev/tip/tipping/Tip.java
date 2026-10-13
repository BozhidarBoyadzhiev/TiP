package dev.tip.tipping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tip row. Only inserted through JPA; every status change goes through a conditional update in
 * {@link TipRepository}. {@code reaction}, {@code flagged} and {@code ai_status} keep their database
 * defaults and belong to the AI feature, so they are not mapped here.
 */
@Entity
@Table(name = "tip")
class Tip {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(length = 280, updatable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TipStatus status;

    @Column(name = "paypal_order_id", length = 64)
    private String paypalOrderId;

    @Column(name = "paypal_capture_id", length = 64)
    private String paypalCaptureId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    /** A sample tip, inserted by the dashboard's sample set, never by JPA. */
    @Column(insertable = false, updatable = false)
    private boolean demo;

    protected Tip() {}

    Tip(UUID id, UUID deviceId, long amountMinor, String currency, String message, Instant createdAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.message = message;
        this.status = TipStatus.CREATED;
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }

    UUID deviceId() {
        return deviceId;
    }

    TipStatus status() {
        return status;
    }

    String paypalCaptureId() {
        return paypalCaptureId;
    }

    String paypalOrderId() {
        return paypalOrderId;
    }

    boolean demo() {
        return demo;
    }
}
