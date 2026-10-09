package dev.tip.owners;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * In memory token buckets for sign in attempts: 5 per minute per client IP and 5 per minute per email,
 * counting every attempt, successful or not. Idle buckets expire. Single instance only.
 */
@Component
class LoginRateLimiter {

    static final int ATTEMPTS_PER_MINUTE = 5;

    private final Cache<String, Bucket> byIp = newCache();
    private final Cache<String, Bucket> byEmail = newCache();

    /** Takes one attempt from both buckets. False when either is empty. */
    boolean tryAcquire(String clientIp, String email) {
        boolean ipAllowed = byIp.get(clientIp, key -> newBucket()).tryConsume(1);
        boolean emailAllowed =
                byEmail.get(OwnerEmails.normalize(email), key -> newBucket()).tryConsume(1);
        return ipAllowed && emailAllowed;
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(5))
                .maximumSize(100_000)
                .build();
    }

    private static Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(ATTEMPTS_PER_MINUTE)
                        .refillGreedy(ATTEMPTS_PER_MINUTE, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
