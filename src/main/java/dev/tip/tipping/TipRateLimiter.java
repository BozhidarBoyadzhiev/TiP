package dev.tip.tipping;

import dev.tip.shared.ClientRateLimiter;
import dev.tip.shared.RateLimitProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Per client IP limits on the public tip endpoints, from {@link RateLimitProperties}: order creation (10 a minute by
 * default; each costs a PayPal call) and capture (30 a minute; a tipper may retry a capture safely).
 */
@Component
class TipRateLimiter {

    private final ClientRateLimiter orders;
    private final ClientRateLimiter captures;

    TipRateLimiter(RateLimitProperties limits) {
        orders = new ClientRateLimiter("tip_order", limits.tipOrdersPerMinute(), Duration.ofMinutes(1));
        captures = new ClientRateLimiter("tip_capture", limits.capturesPerMinute(), Duration.ofMinutes(1));
    }

    void checkOrder(String clientIp) {
        if (!orders.tryAcquire(clientIp)) {
            throw new TipExceptions.RateLimited();
        }
    }

    void checkCapture(String clientIp) {
        if (!captures.tryAcquire(clientIp)) {
            throw new TipExceptions.RateLimited();
        }
    }
}
