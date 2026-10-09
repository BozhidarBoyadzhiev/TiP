package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs the migrations step by step in a fresh schema each, to test what a migration does to data that
 * existed before it.
 */
class MigrationTest extends IntegrationTest {

    private static final String PLACEHOLDER_OWNER = "00000000-0000-0000-0000-000000000000";

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Test
    void placeholderOwnerIsRemovedOnceItOwnsNoDevices() {
        String schema = migrateWithAPreOwnersDeviceUpTo2();
        jdbc.sql("UPDATE " + schema + ".device SET owner_id = ?")
                .param(insertOwner(schema))
                .update();

        migrate(schema, "3");

        assertThat(placeholderOwners(schema)).isZero();
    }

    @Test
    void placeholderOwnerStaysWhileItStillOwnsADevice() {
        String schema = migrateWithAPreOwnersDeviceUpTo2();

        migrate(schema, "3");

        assertThat(placeholderOwners(schema)).isOne();
    }

    private String migrateWithAPreOwnersDeviceUpTo2() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        migrate(schema, "1");
        jdbc.sql("INSERT INTO " + schema
                        + ".device (id, slug, display_name, created_at) VALUES (?, 'old-panda', 'Old', now())")
                .param(UUID.randomUUID())
                .update();
        migrate(schema, "2");
        assertThat(placeholderOwners(schema)).isOne();
        return schema;
    }

    private UUID insertOwner(String schema) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema + ".owner (id, email, password_hash, display_name, created_at)"
                        + " VALUES (?, 'real@example.com', '{bcrypt}!', 'Real', now())")
                .param(id)
                .update();
        return id;
    }

    private long placeholderOwners(String schema) {
        return jdbc.sql("SELECT count(*) FROM " + schema + ".owner WHERE id = ?::uuid")
                .param(PLACEHOLDER_OWNER)
                .query(Long.class)
                .single();
    }

    private void migrate(String schema, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
