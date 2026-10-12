package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tip.IntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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

    @Test
    void oldReactionsMapOntoTheNewModel() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        migrate(schema, "3");
        UUID ownerId = insertOwner(schema);
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema + ".device (id, slug, display_name, owner_id, secret_hash, created_at)"
                        + " VALUES (?, 'old-panda', 'Old', ?, decode(repeat('ab', 32), 'hex'), now())")
                .params(deviceId, ownerId)
                .update();
        UUID positive = insertReactedTip(schema, deviceId, "POSITIVE", "DONE");
        UUID neutral = insertReactedTip(schema, deviceId, "NEUTRAL", "DONE");
        UUID negative = insertReactedTip(schema, deviceId, "NEGATIVE", "DONE");
        UUID skipped = insertReactedTip(schema, deviceId, "NEUTRAL", "SKIPPED");
        UUID failed = insertReactedTip(schema, deviceId, "NEUTRAL", "FAILED");

        migrate(schema, "4");

        assertThat(tipColumns(schema, positive)).containsExactly("WARM", "WARM");
        assertThat(tipColumns(schema, neutral)).containsExactly("PLAIN", "PLAIN");
        assertThat(tipColumns(schema, negative)).containsExactly("PLAIN", "PLAIN");
        assertThat(tipColumns(schema, skipped)).containsExactly("PLAIN", null);
        assertThat(tipColumns(schema, failed)).containsExactly("PLAIN", null);
        assertThat(jdbc.sql("SELECT reaction FROM " + schema + ".reaction_delivery WHERE tip_id IN (?, ?, ?)"
                                + " ORDER BY reaction")
                        .params(positive, neutral, negative)
                        .query(String.class)
                        .list())
                .containsExactly("PLAIN", "PLAIN", "WARM");
        assertThat(jdbc.sql("SELECT owner_note FROM " + schema + ".tip WHERE owner_note IS NOT NULL")
                        .query(String.class)
                        .list())
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POSITIVE", "NEUTRAL", "NEGATIVE", "warm"})
    void newConstraintsRejectOldReactions(String old) {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        migrate(schema, "4");
        UUID ownerId = insertOwner(schema);
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema + ".device (id, slug, display_name, owner_id, secret_hash, created_at)"
                        + " VALUES (?, 'new-panda', 'New', ?, decode(repeat('ab', 32), 'hex'), now())")
                .params(deviceId, ownerId)
                .update();
        UUID tipId = insertReactedTip(schema, deviceId, "WARM", "DONE");

        assertThatThrownBy(() -> jdbc.sql("UPDATE " + schema + ".tip SET reaction = ? WHERE id = ?")
                        .params(old, tipId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE " + schema + ".tip SET message_kind = ? WHERE id = ?")
                        .params(old, tipId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE " + schema + ".reaction_delivery SET reaction = ? WHERE tip_id = ?")
                        .params(old, tipId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE " + schema + ".tip SET owner_note = ? WHERE id = ?")
                        .params("n".repeat(101), tipId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existingDeliveriesRepeatOnceAndRepeatsStayWithin1To5() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        migrate(schema, "4");
        UUID ownerId = insertOwner(schema);
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema + ".device (id, slug, display_name, owner_id, secret_hash, created_at)"
                        + " VALUES (?, 'old-panda', 'Old', ?, decode(repeat('ab', 32), 'hex'), now())")
                .params(deviceId, ownerId)
                .update();
        UUID tipId = insertReactedTip(schema, deviceId, "WARM", "DONE");

        migrate(schema, "5");

        assertThat(jdbc.sql("SELECT repeats FROM " + schema + ".reaction_delivery WHERE tip_id = ?")
                        .param(tipId)
                        .query(Integer.class)
                        .single())
                .isOne();
        for (int invalid : new int[] {0, 6}) {
            assertThatThrownBy(
                            () -> jdbc.sql("UPDATE " + schema + ".reaction_delivery SET repeats = ? WHERE tip_id = ?")
                                    .params(invalid, tipId)
                                    .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void existingOwnersGetLondonAndExistingRowsKeepWorkingWithoutTheNewColumns() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        migrate(schema, "5");
        UUID ownerId = insertOwner(schema);
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema + ".device (id, slug, display_name, owner_id, secret_hash, created_at)"
                        + " VALUES (?, 'old-panda', 'Old', ?, decode(repeat('ab', 32), 'hex'), now())")
                .params(deviceId, ownerId)
                .update();
        UUID tipId = insertReactedTip(schema, deviceId, "WARM", "DONE");

        migrate(schema, "6");

        assertThat(jdbc.sql("SELECT time_zone FROM " + schema + ".owner WHERE id = ?")
                        .param(ownerId)
                        .query(String.class)
                        .single())
                .isEqualTo("Europe/London");
        assertThat(jdbc.sql("SELECT message_language, message_english, requested_song, requested_song_key FROM "
                                + schema + ".tip WHERE id = ?")
                        .param(tipId)
                        .query()
                        .singleRow())
                .containsOnlyKeys("message_language", "message_english", "requested_song", "requested_song_key")
                .allSatisfy((column, value) -> assertThat(value).isNull());
        // The previous release inserts owners and tips without the new columns.
        UUID newerOwner = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema + ".owner (id, email, password_hash, display_name, created_at)"
                        + " VALUES (?, 'newer@example.com', '{bcrypt}!', 'Newer', now())")
                .param(newerOwner)
                .update();
        insertReactedTip(schema, deviceId, "PLAIN", "SKIPPED");
        assertThat(jdbc.sql("SELECT last_seen_at FROM " + schema + ".device WHERE id = ?")
                        .param(deviceId)
                        .query()
                        .singleRow())
                .containsEntry("last_seen_at", null);
    }

    @Test
    void existingFlaggedTipsStayHiddenAndTheVerdictTakesOnlyNotAbusive() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        migrate(schema, "6");
        UUID ownerId = insertOwner(schema);
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema + ".device (id, slug, display_name, owner_id, secret_hash, created_at)"
                        + " VALUES (?, 'old-panda', 'Old', ?, decode(repeat('ab', 32), 'hex'), now())")
                .params(deviceId, ownerId)
                .update();
        UUID tipId = insertReactedTip(schema, deviceId, "PLAIN", "DONE");
        jdbc.sql("UPDATE " + schema + ".tip SET flagged = true, flag_reason = 'insult' WHERE id = ?")
                .param(tipId)
                .update();

        migrate(schema, "7");

        assertThat(jdbc.sql("SELECT flagged, flag_reason, owner_verdict, owner_verdict_at FROM " + schema
                                + ".tip WHERE id = ?")
                        .param(tipId)
                        .query()
                        .singleRow())
                .containsEntry("flagged", true)
                .containsEntry("flag_reason", "insult")
                .containsEntry("owner_verdict", null)
                .containsEntry("owner_verdict_at", null);
        // The previous release inserts tips without the new columns.
        insertReactedTip(schema, deviceId, "PLAIN", "SKIPPED");
        jdbc.sql("UPDATE " + schema + ".tip SET owner_verdict = 'NOT_ABUSIVE', owner_verdict_at = now() WHERE id = ?")
                .param(tipId)
                .update();
        for (String invalid : List.of("ABUSIVE", "not_abusive", "")) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE " + schema + ".tip SET owner_verdict = ? WHERE id = ?")
                            .params(invalid, tipId)
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /** A captured tip with the given reaction, plus its delivery row. */
    private UUID insertReactedTip(String schema, UUID deviceId, String reaction, String aiStatus) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema + ".tip (id, device_id, amount_minor, currency, status, paypal_order_id,"
                        + " reaction, ai_status, created_at, captured_at)"
                        + " VALUES (?, ?, 200, 'GBP', 'CAPTURED', ?, ?, ?, now(), now())")
                .params(id, deviceId, "ORDER-" + id, reaction, aiStatus)
                .update();
        jdbc.sql("INSERT INTO " + schema + ".reaction_delivery (tip_id, device_id, reaction, reacted_at)"
                        + " VALUES (?, ?, ?, now())")
                .params(id, deviceId, reaction)
                .update();
        return id;
    }

    /** {@code reaction} and {@code message_kind}. */
    private List<String> tipColumns(String schema, UUID tipId) {
        return jdbc.sql("SELECT reaction, message_kind FROM " + schema + ".tip WHERE id = ?")
                .param(tipId)
                .query((rs, row) -> Arrays.asList(rs.getString("reaction"), rs.getString("message_kind")))
                .single();
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
