package dev.tip;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads and inserts rows directly, for tests that need data in a given state. Every call uses fresh ids. */
@Component
public class TestData {

    private final JdbcClient jdbc;

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String uniqueSlug() {
        return "panda-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();
    }

    public UUID device(String slug, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO device (id, slug, display_name, created_at) VALUES (?, ?, ?, now())")
                .params(id, slug, displayName)
                .update();
        return id;
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

    public void setCaptureId(UUID tipId, String captureId) {
        jdbc.sql("UPDATE tip SET paypal_capture_id = ? WHERE id = ?")
                .params(captureId, tipId)
                .update();
    }

    public Map<String, Object> tipRow(UUID tipId) {
        return jdbc.sql("SELECT * FROM tip WHERE id = ?").param(tipId).query().singleRow();
    }

    public String tipStatus(UUID tipId) {
        return (String) tipRow(tipId).get("status");
    }

    public List<Map<String, Object>> tipsOfDevice(UUID deviceId) {
        return jdbc.sql("SELECT * FROM tip WHERE device_id = ?")
                .param(deviceId)
                .query()
                .listOfRows();
    }

    public long webhookEvents(String eventId) {
        return jdbc.sql("SELECT count(*) FROM paypal_webhook_event WHERE event_id = ?")
                .param(eventId)
                .query(Long.class)
                .single();
    }
}
