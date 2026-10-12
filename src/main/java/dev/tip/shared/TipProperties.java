package dev.tip.shared;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Tip amounts, currency and public URL, bound from the {@code TIP_*} environment variables. */
@Validated
@ConfigurationProperties("tip")
public record TipProperties(
        @NotBlank String publicBaseUrl,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Positive long minMinor,
        @Positive long maxMinor,
        @NotEmpty List<@Positive Long> presetAmountsMinor) {

    public TipProperties {
        presetAmountsMinor = presetAmountsMinor == null ? null : List.copyOf(presetAmountsMinor);
    }

    /** The origin of the public base URL: scheme, host and port, without a path or trailing slash. */
    public String publicOrigin() {
        URI uri = URI.create(publicBaseUrl.strip());
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    @AssertTrue(message = "minMinor must not exceed maxMinor and presets must lie between them")
    public boolean isRangeConsistent() {
        if (minMinor > maxMinor) {
            return false;
        }
        return presetAmountsMinor == null
                || presetAmountsMinor.stream().allMatch(p -> p != null && p >= minMinor && p <= maxMinor);
    }
}
