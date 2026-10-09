package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerProvisioning;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class DemoDeviceSeederTest extends IntegrationTest {

    @Autowired
    OwnerProvisioning owners;

    @Autowired
    DeviceRepository devices;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    TestData data;

    @Test
    void startupSeededTheDemoOwnerAndTheirDevice() {
        Map<String, Object> owner = data.ownerRow("demo-owner@example.com");
        Map<String, Object> device = data.deviceRow("demo-panda");

        assertThat(owner).containsEntry("display_name", "Demo Owner");
        assertThat(passwordEncoder.matches("test-owner-password", (String) owner.get("password_hash")))
                .isTrue();
        assertThat(device).containsEntry("owner_id", owner.get("id"));
        assertThat((byte[]) device.get("secret_hash")).isEqualTo(sha256(new byte[32]));
    }

    @Test
    void createsOwnerAndDeviceAndARerunChangesNothing() {
        String email = TestData.uniqueEmail();
        String slug = TestData.uniqueSlug();
        DemoOwnerProperties owner = new DemoOwnerProperties(email.toUpperCase(), "first-password-123", "Seeded Owner");
        DemoDeviceProperties device = new DemoDeviceProperties(slug, "Seeded Panda", secret(1));

        run(owner, device);
        Map<String, Object> ownerRow = data.ownerRow(email);
        Map<String, Object> deviceRow = data.deviceRow(slug);
        run(new DemoOwnerProperties(email, "a-different-password", "Other Name"), device);

        assertThat(data.ownerRow(email)).isEqualTo(ownerRow);
        assertThat(data.deviceRow(slug)).usingRecursiveComparison().isEqualTo(deviceRow);
        assertThat(ownerRow).containsEntry("display_name", "Seeded Owner");
        assertThat(passwordEncoder.matches("first-password-123", (String) ownerRow.get("password_hash")))
                .isTrue();
        assertThat(deviceRow)
                .containsEntry("owner_id", ownerRow.get("id"))
                .containsEntry("display_name", "Seeded Panda");
        assertThat((byte[]) deviceRow.get("secret_hash")).isEqualTo(sha256(bytes(1)));
    }

    @Test
    void changedDeviceSecretReplacesTheStoredHash() {
        DemoOwnerProperties owner = new DemoOwnerProperties(TestData.uniqueEmail(), "owner-password-123", null);
        String slug = TestData.uniqueSlug();
        run(owner, new DemoDeviceProperties(slug, null, secret(1)));

        run(owner, new DemoDeviceProperties(slug, null, secret(2)));

        assertThat((byte[]) data.deviceRow(slug).get("secret_hash")).isEqualTo(sha256(bytes(2)));
    }

    @Test
    void existingDeviceIsTakenOverByTheDemoOwner() {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Unclaimed Panda");
        String email = TestData.uniqueEmail();

        run(
                new DemoOwnerProperties(email, "owner-password-123", null),
                new DemoDeviceProperties(slug, null, secret(3)));

        assertThat(data.deviceRow(slug))
                .containsEntry("owner_id", data.ownerRow(email).get("id"));
        assertThat((byte[]) data.deviceRow(slug).get("secret_hash")).isEqualTo(sha256(bytes(3)));
    }

    @Test
    void demoDeviceWithoutDemoOwnerIsRefused() {
        DemoDeviceProperties device = new DemoDeviceProperties(TestData.uniqueSlug(), null, secret(4));

        assertThatThrownBy(() -> run(new DemoOwnerProperties(null, null, null), device))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TIP_DEMO_OWNER_EMAIL");
    }

    @Test
    void passwordIsHiddenFromToString() {
        assertThat(new DemoOwnerProperties("a@example.com", "secret-password-1", "A").toString())
                .doesNotContain("secret-password-1");
        assertThat(new DemoDeviceProperties("demo-panda", null, secret(5)).toString())
                .doesNotContain(secret(5));
    }

    private void run(DemoOwnerProperties owner, DemoDeviceProperties device) {
        DemoDeviceSeeder seeder = new DemoDeviceSeeder(owner, device, owners, devices, Clock.systemUTC());
        transactions.executeWithoutResult(status -> seeder.run(new DefaultApplicationArguments()));
    }

    private static byte[] bytes(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private static String secret(int fill) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(fill));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
