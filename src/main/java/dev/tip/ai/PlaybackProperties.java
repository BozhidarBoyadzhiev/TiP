package dev.tip.ai;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * A tip plays on the device only when its capture landed within {@code window} of TiP's first capture attempt: the
 * tipper is likely still there. A capture settled later (a late webhook, the pending or abandoned tips job, a
 * revived tip) or one TiP never attempted is recorded and classified, but not played.
 */
@Validated
@ConfigurationProperties("tip.ai.playback")
record PlaybackProperties(@NotNull Duration window) {}
