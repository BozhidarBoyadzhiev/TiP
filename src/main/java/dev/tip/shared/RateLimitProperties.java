package dev.tip.shared;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per client IP limits, bound from the {@code TIP_RATE_*} environment variables. The defaults sit well above what
 * an honest tipper, judge or visitor does; see {@code docs/DEPLOYMENT.md}.
 */
@Validated
@ConfigurationProperties("tip.rate-limits")
public record RateLimitProperties(
        @Positive int tipOrdersPerMinute,
        @Positive int capturesPerMinute,
        @Positive int judgeSessionsPerHour,
        @Positive int demoSessionsPerMinute,
        @Positive int resumesPerMinute) {}
