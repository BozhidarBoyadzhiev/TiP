package dev.tip.devices;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tip.shared.ClientKeys;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Failed device handshakes per client IP (an IPv6 client by its /64, {@link ClientKeys}): 10 per minute. The secret cannot be brute forced, but every
 * attempt costs a hash and a database read. An IP over the limit is turned away before either, even
 * with the right secret. In memory, like the rest of the single instance backend.
 */
@Component
class HandshakeFailureLimiter {

    static final int FAILURES_PER_MINUTE = 10;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(100_000)
            .build();

    boolean blocked(String clientIp) {
        Bucket bucket = buckets.getIfPresent(ClientKeys.of(clientIp));
        return bucket != null && bucket.getAvailableTokens() < 1;
    }

    void recordFailure(String clientIp) {
        buckets.get(ClientKeys.of(clientIp), ip -> newBucket()).tryConsume(1);
    }

    private static Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(FAILURES_PER_MINUTE)
                        .refillGreedy(FAILURES_PER_MINUTE, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
