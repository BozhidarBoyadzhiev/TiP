package dev.tip.devices;

import dev.tip.devices.api.DeviceSlug;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Optional demo device created on startup, bound from {@code TIP_DEMO_DEVICE_SLUG} and {@code
 * TIP_DEMO_DEVICE_NAME}. A blank slug disables the seeder.
 */
@Validated
@ConfigurationProperties("tip.demo-device")
record DemoDeviceProperties(
        @Pattern(regexp = DeviceSlug.PATTERN) String slug,
        @Size(max = 80) String name) {

    DemoDeviceProperties {
        slug = slug == null || slug.isBlank() ? null : slug.strip();
        name = name == null || name.isBlank() ? null : name.strip();
    }

    boolean enabled() {
        return slug != null;
    }

    String displayName() {
        return name != null ? name : slug;
    }
}
