package dev.tip.ai;

import dev.tip.ai.api.AiAvailability;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stops calling the model after an error no retry can fix: the spend limit or credit balance (402, or a 400 naming
 * the usage limit or credit balance) and authentication or permission errors (401, 403). While open, every call is
 * skipped; after {@link #REST} one trial call goes through. Success, or an answer of another kind, closes it; another
 * such error opens it for {@link #REST} again. Other failures (429, 5xx, timeouts) never open it: they cost one call
 * each and are never retried. In memory, like the rest of the single instance backend.
 */
@Component
class AiBreaker implements AiAvailability {

    static final Duration REST = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(AiBreaker.class);

    private final Clock clock;
    private Instant openUntil;
    private boolean trialInFlight;

    AiBreaker(Clock clock) {
        this.clock = clock;
    }

    /** True when a call may go out now. While open, true only for the one trial after the rest. */
    synchronized boolean tryCall() {
        if (openUntil == null) {
            return true;
        }
        if (clock.instant().isBefore(openUntil) || trialInFlight) {
            return false;
        }
        trialInFlight = true;
        return true;
    }

    synchronized void succeeded() {
        trialInFlight = false;
        close();
    }

    /** A call the API answered with {@code status}. */
    synchronized void failed(int status, String errorText) {
        trialInFlight = false;
        if (opens(status, errorText)) {
            openUntil = clock.instant().plus(REST);
            log.info("ai_breaker_opened status={} until={}", status, openUntil);
        } else {
            close();
        }
    }

    /** A call that never got an answer: a timeout or a lost connection. Says nothing about the limit. */
    synchronized void failedWithoutAnswer() {
        trialInFlight = false;
    }

    @Override
    public synchronized boolean resting() {
        return openUntil != null;
    }

    private void close() {
        if (openUntil != null) {
            openUntil = null;
            log.info("ai_breaker_closed");
        }
    }

    static boolean opens(int status, String errorText) {
        if (status == 401 || status == 402 || status == 403) {
            return true;
        }
        String text = errorText == null ? "" : errorText.toLowerCase(Locale.ROOT);
        return status == 400 && (text.contains("credit balance") || text.contains("usage limit"));
    }
}
