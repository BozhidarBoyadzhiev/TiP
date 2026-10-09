package dev.tip;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Inserts rows directly, for tests that need data in a given state. Every call uses fresh ids. */
@Component
public class TestData {

    private final JdbcClient jdbc;

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID device(String slug, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO device (id, slug, display_name, created_at) VALUES (?, ?, ?, now())")
                .params(id, slug, displayName)
                .update();
        return id;
    }

    public String uniqueSlug() {
        return "panda-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public UUID tip(UUID deviceId, String status, String paypalOrderId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tip (id, device_id, amount_minor, currency, status, paypal_order_id, created_at)
                        VALUES (?, ?, 200, 'GBP', ?, ?, ?)
                        """)
                .params(id, deviceId, status, paypalOrderId, Timestamp.from(createdAt))
                .update();
        return id;
    }
}
