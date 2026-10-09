package dev.tip.paypal;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** PayPal REST credentials and webhook id, bound from the {@code PAYPAL_*} environment variables. */
@Validated
@ConfigurationProperties("paypal")
record PayPalProperties(
        @NotBlank String baseUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String webhookId) {

    @Override
    public String toString() {
        return "PayPalProperties[baseUrl=" + baseUrl + ", clientId=" + clientId + "]";
    }
}
