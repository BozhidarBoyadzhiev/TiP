package dev.tip.devices;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Optional demo owner the demo device belongs to, bound from {@code TIP_DEMO_OWNER_EMAIL}, {@code
 * TIP_DEMO_OWNER_PASSWORD} and {@code TIP_DEMO_OWNER_NAME}. A blank email disables it. The password is
 * only used when the owner is created and never appears in logs or error messages.
 */
@Validated
@ConfigurationProperties("tip.demo-owner")
record DemoOwnerProperties(
        @Email @Size(max = 254) String email,
        String password,
        @Size(max = 80) String name) {

    static final int MIN_PASSWORD_LENGTH = 12;

    /** Bcrypt only reads the first 72 bytes. */
    static final int MAX_PASSWORD_BYTES = 72;

    DemoOwnerProperties {
        email = email == null || email.isBlank() ? null : email.strip();
        password = password == null || password.isEmpty() ? null : password;
        name = name == null || name.isBlank() ? null : name.strip();
    }

    boolean enabled() {
        return email != null;
    }

    String displayName() {
        return name != null ? name : email;
    }

    @AssertTrue(message = "TIP_DEMO_OWNER_PASSWORD must be 12 to 72 characters when a demo owner is set")
    boolean isPasswordValid() {
        return !enabled()
                || (password != null
                        && password.length() >= MIN_PASSWORD_LENGTH
                        && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES);
    }

    @Override
    public String toString() {
        return "DemoOwnerProperties[email=" + email + ", name=" + name + "]";
    }
}
