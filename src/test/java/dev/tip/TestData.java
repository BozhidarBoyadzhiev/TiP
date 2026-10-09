package dev.tip;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Reads and inserts rows directly, for tests that need data in a given state. Every call uses fresh ids. */
@Component
public class TestData {

    public static final String OWNER_PASSWORD = "correct-horse-battery";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public TestData(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public static String uniqueSlug() {
        return "panda-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();
    }

    public static String uniqueEmail() {
        return "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Words only, so it appears verbatim in a JSON request body. */
    public static String uniqueMessage(String text) {
        return text + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    public UUID owner(String email, String displayName, String rawPassword) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO owner (id, email, password_hash, display_name, created_at) VALUES (?, ?, ?, ?, now())")
                .params(id, email, passwordEncoder.encode(rawPassword), displayName)
                .update();
        return id;
    }

    public UUID owner() {
        return owner(uniqueEmail(), "Test Owner", OWNER_PASSWORD);
    }

    public UUID device(UUID ownerId, String slug, String displayName) {
        UUID id = UUID.randomUUID();
        byte[] secretHash = new byte[32];
        RANDOM.nextBytes(secretHash);
        jdbc.sql("INSERT INTO device (id, slug, display_name, owner_id, secret_hash, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, now())")
                .params(id, slug, displayName, ownerId, secretHash)
                .update();
        return id;
    }

    public UUID device(String slug, String displayName) {
        return device(owner(), slug, displayName);
    }

    public UUID device() {
        return device(uniqueSlug(), "Test Panda");
    }

    public UUID tip(UUID deviceId, String status, String paypalOrderId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tip (id, device_id, amount_minor, currency, status, paypal_order_id, created_at)"
                        + " VALUES (?, ?, 200, 'GBP', ?, ?, ?)")
                .params(id, deviceId, status, paypalOrderId, Timestamp.from(createdAt))
                .update();
        return id;
    }

    /** A CREATED tip with an order and a message, ready to be captured. */
    public UUID tipWithMessage(UUID deviceId, String paypalOrderId, String message) {
        UUID id = tip(deviceId, "CREATED", paypalOrderId, Instant.now());
        jdbc.sql("UPDATE tip SET message = ? WHERE id = ?").params(message, id).update();
        return id;
    }

    /** A CAPTURED tip whose classification has not started, as if the process died right after capture. */
    public UUID capturedTip(UUID deviceId, String message) {
        UUID id = tip(deviceId, "CAPTURED", uniqueId("ORDER"), Instant.now());
        jdbc.sql("UPDATE tip SET message = ?, paypal_capture_id = ?, captured_at = now() WHERE id = ?")
                .params(message, uniqueId("CAPTURE"), id)
                .update();
        return id;
    }

    public void setCaptureId(UUID tipId, String captureId) {
        jdbc.sql("UPDATE tip SET paypal_capture_id = ? WHERE id = ?")
                .params(captureId, tipId)
                .update();
    }

    public void setAiState(UUID tipId, String aiStatus, Instant claimedAt, int attempts) {
        jdbc.sql("UPDATE tip SET ai_status = ?, ai_claimed_at = ?, ai_attempts = ? WHERE id = ?")
                .params(aiStatus, claimedAt == null ? null : Timestamp.from(claimedAt), attempts, tipId)
                .update();
    }

    public Map<String, Object> tipRow(UUID tipId) {
        return jdbc.sql("SELECT * FROM tip WHERE id = ?").param(tipId).query().singleRow();
    }

    public String tipStatus(UUID tipId) {
        return (String) tipRow(tipId).get("status");
    }

    public String aiStatus(UUID tipId) {
        return (String) tipRow(tipId).get("ai_status");
    }

    public List<Map<String, Object>> tipsOfDevice(UUID deviceId) {
        return jdbc.sql("SELECT * FROM tip WHERE device_id = ?")
                .param(deviceId)
                .query()
                .listOfRows();
    }

    public Map<String, Object> deviceRow(String slug) {
        return jdbc.sql("SELECT * FROM device WHERE slug = ?")
                .param(slug)
                .query()
                .singleRow();
    }

    public Map<String, Object> ownerRow(String email) {
        return jdbc.sql("SELECT * FROM owner WHERE email = ?")
                .param(email)
                .query()
                .singleRow();
    }

    public long webhookEvents(String eventId) {
        return jdbc.sql("SELECT count(*) FROM paypal_webhook_event WHERE event_id = ?")
                .param(eventId)
                .query(Long.class)
                .single();
    }
}
