package dev.tip.ai;

/**
 * A model call failed (an API error, a timeout, a lost connection) or was skipped while the breaker rests. {@code
 * getMessage()} never contains a tip message or model output, so it is safe to log.
 */
final class ModelUnavailable extends RuntimeException {

    ModelUnavailable(String reason) {
        super(reason);
    }
}
