package dev.tip.tipping;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tip.shared.ClientKeys;
import dev.tip.shared.LogSafe;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Webhooks PayPal rejected, per client IP (an IPv6 client by its /64, {@link ClientKeys}): 10 per minute. Each one
 * cost an outbound verification call, so an IP over the limit is turned away before the next. PayPal's own deliveries
 * never fail verification and are never limited. In memory, like the rest of the single instance backend.
 */
@Component
class WebhookFailureLimiter {

    static final int REJECTED_PER_MINUTE = 10;

    private static final Logger log = LoggerFactory.getLogger(WebhookFailureLimiter.class);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(100_000)
            .build();

    boolean blocked(String clientIp) {
        String key = ClientKeys.of(clientIp);
        Bucket bucket = buckets.getIfPresent(key);
        if (bucket == null || bucket.getAvailableTokens() >= 1) {
            return false;
        }
        log.warn("rate_limited endpoint=paypal_webhook ip={}", LogSafe.ip(key));
        return true;
    }

    void recordRejected(String clientIp) {
        buckets.get(ClientKeys.of(clientIp), key -> newBucket()).tryConsume(1);
    }

    private static Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(REJECTED_PER_MINUTE)
                        .refillGreedy(REJECTED_PER_MINUTE, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
