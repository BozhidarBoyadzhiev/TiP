package dev.tip.tipping;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * In memory token bucket per client IP for tip creation: 10 per minute. Idle buckets expire so the map
 * stays small. Single instance only, like the rest of the backend.
 */
@Component
class TipRateLimiter {

    static final int TIPS_PER_MINUTE = 10;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(100_000)
            .build();

    void check(String clientIp) {
        Bucket bucket = buckets.get(clientIp, ip -> newBucket());
        if (!bucket.tryConsume(1)) {
            throw new TipExceptions.RateLimited();
        }
    }

    private static Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(TIPS_PER_MINUTE)
                        .refillGreedy(TIPS_PER_MINUTE, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
