package dev.tip.ai;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Claude settings, bound from {@code TIP_AI_ENABLED}, {@code TIP_AI_MODEL}, {@code ANTHROPIC_API_KEY}
 * and {@code ANTHROPIC_BASE_URL}. The key is required only while AI is enabled.
 */
@Validated
@ConfigurationProperties("tip.ai")
record AiProperties(
        boolean enabled,
        @NotBlank String model,
        String apiKey,
        @NotBlank String baseUrl,
        @NotNull Duration timeout) {

    @AssertTrue(message = "ANTHROPIC_API_KEY must be set while TIP_AI_ENABLED is true")
    boolean isApiKeyPresentWhenEnabled() {
        return !enabled || (apiKey != null && !apiKey.isBlank());
    }

    @Override
    public String toString() {
        return "AiProperties[enabled=" + enabled + ", model=" + model + ", baseUrl=" + baseUrl + ", timeout=" + timeout
                + "]";
    }
}
