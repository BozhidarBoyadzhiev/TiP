package dev.tip.paypal;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * PayPal REST credentials and webhook id, bound from the {@code PAYPAL_*} environment variables. {@code
 * verifyReadTimeout} bounds the webhook verification call; only the test profile shortens it.
 */
@Validated
@ConfigurationProperties("paypal")
record PayPalProperties(
        @NotBlank String baseUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String webhookId,
        @DefaultValue("PT15S") Duration verifyReadTimeout) {

    @Override
    public String toString() {
        return "PayPalProperties[baseUrl=" + baseUrl + ", clientId=" + clientId + "]";
    }
}
