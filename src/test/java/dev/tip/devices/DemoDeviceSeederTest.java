package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerProvisioning;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
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
        assertThat((byte[]) device.get("secret_hash")).isEqualTo(sha256(decode(DEMO_DEVICE_SECRET)));
    }

    @Test
    void createsOwnerAndDeviceAndARerunChangesNothing() {
        String email = TestData.uniqueEmail();
        String slug = TestData.uniqueSlug();
        DemoOwnerProperties owner = new DemoOwnerProperties(email.toUpperCase(), "first-password-123", "Seeded Owner");
        String secret = TestData.randomSecret();
        DemoDeviceProperties device = new DemoDeviceProperties(slug, "Seeded Panda", secret);

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
        assertThat((byte[]) deviceRow.get("secret_hash")).isEqualTo(sha256(decode(secret)));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void changedDeviceSecretReplacesTheStoredHash(CapturedOutput output) {
        DemoOwnerProperties owner = new DemoOwnerProperties(TestData.uniqueEmail(), "owner-password-123", null);
        String slug = TestData.uniqueSlug();
        run(owner, new DemoDeviceProperties(slug, null, TestData.randomSecret()));
        String rotated = TestData.randomSecret();

        run(owner, new DemoDeviceProperties(slug, null, rotated));

        assertThat((byte[]) data.deviceRow(slug).get("secret_hash")).isEqualTo(sha256(decode(rotated)));
        assertThat(output.getOut().lines().filter(line -> line.contains("Rotated the secret of demo device " + slug)))
                .singleElement()
                .asString()
                .contains(" INFO ");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsTheDeviceIdButNeverTheSecret(CapturedOutput output) {
        DemoOwnerProperties owner = new DemoOwnerProperties(TestData.uniqueEmail(), "owner-password-123", null);
        String slug = TestData.uniqueSlug();
        String secret = TestData.randomSecret();

        run(owner, new DemoDeviceProperties(slug, null, secret));
        run(owner, new DemoDeviceProperties(slug, null, secret));

        Object id = data.deviceRow(slug).get("id");
        assertThat(output.getOut())
                .contains("Created demo device " + slug + " with id " + id)
                .contains("Demo device " + slug + " has id " + id)
                .doesNotContain(secret);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void deviceOfAnotherOwnerStaysWithThemOutsideDev(CapturedOutput output) {
        String slug = TestData.uniqueSlug();
        String theirSecret = TestData.randomSecret();
        UUID ownerA = data.owner();
        data.device(ownerA, slug, "Their Panda", theirSecret);
        byte[] hashBefore = (byte[]) data.deviceRow(slug).get("secret_hash");
        String emailB = TestData.uniqueEmail();
        String secretB = TestData.randomSecret();

        run(new DemoOwnerProperties(emailB, "owner-password-123", null), new DemoDeviceProperties(slug, null, secretB));

        Object ownerB = data.ownerRow(emailB).get("id");
        assertThat(data.deviceRow(slug)).containsEntry("owner_id", ownerA);
        assertThat((byte[]) data.deviceRow(slug).get("secret_hash")).isEqualTo(hashBefore);
        String warning = "Demo device " + slug + " belongs to owner " + ownerA + ", not to the demo owner " + ownerB;
        assertThat(output.getOut())
                .containsOnlyOnce(warning)
                .doesNotContain(emailB, secretB, theirSecret, "Assigned demo device " + slug, "Rotated");
        assertThat(output.getOut().lines().filter(line -> line.contains(warning)))
                .singleElement()
                .asString()
                .contains(" WARN ");
    }

    @Test
    void deviceOfAnotherOwnerMovesToTheDemoOwnerUnderDev() {
        String slug = TestData.uniqueSlug();
        UUID ownerA = data.owner();
        data.device(ownerA, slug, "Their Panda", TestData.randomSecret());
        String emailB = TestData.uniqueEmail();
        String secretB = TestData.randomSecret();

        run(
                new DemoOwnerProperties(emailB, "owner-password-123", null),
                new DemoDeviceProperties(slug, null, secretB),
                DemoDeviceSeeder.DEV_PROFILE);

        assertThat(data.deviceRow(slug))
                .containsEntry("owner_id", data.ownerRow(emailB).get("id"));
        assertThat((byte[]) data.deviceRow(slug).get("secret_hash")).isEqualTo(sha256(decode(secretB)));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void sameSecretOnRestartLeavesTheStoredHashByteIdentical(CapturedOutput output) {
        DemoOwnerProperties owner = validOwner();
        DemoDeviceProperties device = new DemoDeviceProperties(TestData.uniqueSlug(), null, TestData.randomSecret());
        run(owner, device);
        byte[] stored = (byte[]) data.deviceRow(device.slug()).get("secret_hash");

        run(owner, device);

        assertThat((byte[]) data.deviceRow(device.slug()).get("secret_hash")).isEqualTo(stored);
        assertThat(output.getOut()).doesNotContain("Rotated the secret of demo device " + device.slug());
    }

    @Test
    void demoDeviceWithoutDemoOwnerIsRefused() {
        DemoDeviceProperties device = new DemoDeviceProperties(TestData.uniqueSlug(), null, TestData.randomSecret());

        assertThatThrownBy(() -> run(new DemoOwnerProperties(null, null, null), device))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TIP_DEMO_OWNER_EMAIL");
    }

    @Test
    void passwordIsHiddenFromToString() {
        assertThat(new DemoOwnerProperties("a@example.com", "secret-password-1", "A").toString())
                .doesNotContain("secret-password-1");
        String secret = TestData.randomSecret();
        assertThat(new DemoDeviceProperties("demo-panda", null, secret).toString())
                .doesNotContain(secret);
    }

    @Test
    void secretOfIdenticalBytesIsRefusedWithAHowTo() {
        DemoDeviceProperties device = new DemoDeviceProperties(TestData.uniqueSlug(), null, encode(new byte[32]));

        assertThatThrownBy(() -> run(validOwner(), device))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 identical bytes")
                .hasMessageContaining("openssl rand -base64 32");
        assertThat(devices.findBySlug(device.slug())).isEmpty();
    }

    @Test
    void secretWithFewerThan16DistinctBytesIsRefusedWithAHowTo() {
        DemoDeviceProperties device = new DemoDeviceProperties(TestData.uniqueSlug(), null, encode(cycling(15)));

        assertThatThrownBy(() -> run(validOwner(), device))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fewer than 16 distinct byte values")
                .hasMessageContaining("openssl rand -base64 32");
        assertThat(devices.findBySlug(device.slug())).isEmpty();
    }

    @Test
    void secretWithExactly16DistinctBytesIsAccepted() {
        DemoDeviceProperties device = new DemoDeviceProperties(TestData.uniqueSlug(), null, encode(cycling(16)));

        run(validOwner(), device);

        assertThat(devices.findBySlug(device.slug())).isPresent();
    }

    @Test
    void realRandomSecretIsAccepted() {
        String secret = TestData.randomSecret();
        DemoDeviceProperties device = new DemoDeviceProperties(TestData.uniqueSlug(), null, secret);

        run(validOwner(), device);

        assertThat((byte[]) data.deviceRow(device.slug()).get("secret_hash")).isEqualTo(sha256(decode(secret)));
    }

    private static DemoOwnerProperties validOwner() {
        return new DemoOwnerProperties(TestData.uniqueEmail(), "owner-password-123", null);
    }

    /** Runs the seeder as a start with these profiles would; none is how Render runs. */
    private void run(DemoOwnerProperties owner, DemoDeviceProperties device, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        DemoDeviceSeeder seeder = new DemoDeviceSeeder(owner, device, owners, devices, Clock.systemUTC(), environment);
        transactions.executeWithoutResult(status -> seeder.run(new DefaultApplicationArguments()));
    }

    /** 32 bytes cycling through {@code distinct} values. */
    private static byte[] cycling(int distinct) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % distinct);
        }
        return bytes;
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String secret) {
        return Base64.getUrlDecoder().decode(secret);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
