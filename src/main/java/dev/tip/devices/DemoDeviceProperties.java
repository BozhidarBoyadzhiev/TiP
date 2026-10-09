package dev.tip.devices;

import dev.tip.devices.api.DeviceSlug;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Optional demo device created on startup, bound from {@code TIP_DEMO_DEVICE_SLUG}, {@code
 * TIP_DEMO_DEVICE_NAME} and {@code TIP_DEMO_DEVICE_SECRET}. A blank slug disables it. The secret is
 * required with a slug and must be base64url of exactly 32 bytes.
 */
@Validated
@ConfigurationProperties("tip.demo-device")
record DemoDeviceProperties(
        @Pattern(regexp = DeviceSlug.PATTERN) String slug,
        @Size(max = 80) String name,
        String secret) {

    DemoDeviceProperties {
        slug = blankToNull(slug);
        name = blankToNull(name);
        secret = blankToNull(secret);
    }

    boolean enabled() {
        return slug != null;
    }

    String displayName() {
        return name != null ? name : slug;
    }

    @AssertTrue(message = "TIP_DEMO_DEVICE_SECRET must be base64url of exactly 32 bytes when a demo device is set")
    boolean isSecretValid() {
        return !enabled() || DeviceSecrets.decode(secret).isPresent();
    }

    @Override
    public String toString() {
        return "DemoDeviceProperties[slug=" + slug + ", name=" + name + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
