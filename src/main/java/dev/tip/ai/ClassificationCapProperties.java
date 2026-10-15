package dev.tip.ai;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** How many classification calls the whole app makes at most, per UTC hour and per UTC day. */
@Validated
@ConfigurationProperties("tip.ai.cap")
record ClassificationCapProperties(
        @Positive int perHour, @Positive int perDay) {}
