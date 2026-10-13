package dev.tip.ai;

import dev.tip.ai.api.AiAvailability;
import dev.tip.tipping.api.MessageSanitizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stops calling the model after an answer no retry can fix: any 400, 401, 402, 403 or 404, which covers the spend
 * limit, the credit balance, authentication, permissions and a model that does not exist, whatever the API's wording.
 * While open, every call is skipped; after {@link #REST} one trial call goes through. Success, or an answer of another
 * kind, closes it; another such answer opens it for {@link #REST} again. Other failures (429, 5xx, timeouts) never
 * open it: they cost one call each and are never retried. The opening is logged with the status and the API's error
 * type and message, which describe the request, never the prompt or a tip. In memory, like the rest of the single
 * instance backend.
 */
@Component
class AiBreaker implements AiAvailability {

    static final Duration REST = Duration.ofMinutes(10);

    /** Caps the API's error message in the log line. */
    static final int MAX_LOGGED_MESSAGE = 200;

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

    /** A call the API answered with {@code status}, its error type and message, either of which may be null. */
    synchronized void failed(int status, String errorType, String errorMessage) {
        trialInFlight = false;
        if (opens(status)) {
            openUntil = clock.instant().plus(REST);
            log.info(
                    "ai_breaker_opened status={} type={} message={} until={}",
                    status,
                    loggable(errorType),
                    loggable(errorMessage),
                    openUntil);
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

    static boolean opens(int status) {
        return status == 400 || status == 401 || status == 402 || status == 403 || status == 404;
    }

    /** One line of at most {@link #MAX_LOGGED_MESSAGE} characters, or {@code -}. */
    private static String loggable(String text) {
        String clean = MessageSanitizer.sanitize(text);
        if (clean == null) {
            return "-";
        }
        return clean.length() > MAX_LOGGED_MESSAGE ? clean.substring(0, MAX_LOGGED_MESSAGE) : clean;
    }
}
