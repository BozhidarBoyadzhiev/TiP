package dev.tip.shared;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In memory token buckets per client IP for one endpoint: {@code capacity} requests, refilled evenly over {@code
 * period}. The client IP is the one Tomcat resolved from the configured header ({@code getRemoteAddr()}). A
 * refusal logs one WARN {@code rate_limited} line with the endpoint and the hashed IP ({@link LogSafe}). Idle
 * buckets expire; single instance only, like the rest of the backend.
 */
public final class ClientRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ClientRateLimiter.class);

    private static final Duration MIN_IDLE = Duration.ofMinutes(5);

    private final String endpoint;
    private final int capacity;
    private final Duration period;
    private final Cache<String, Bucket> buckets;

    public ClientRateLimiter(String endpoint, int capacity, Duration period) {
        this.endpoint = endpoint;
        this.capacity = capacity;
        this.period = period;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(period.compareTo(MIN_IDLE) > 0 ? period : MIN_IDLE)
                .maximumSize(100_000)
                .build();
    }

    /** Takes one request from the client's bucket. False, with the WARN line, when it is empty. */
    public boolean tryAcquire(String clientIp) {
        if (buckets.get(clientIp, ip -> newBucket()).tryConsume(1)) {
            return true;
        }
        log.warn("rate_limited endpoint={} ip={}", endpoint, LogSafe.ip(clientIp));
        return false;
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, period)
                        .build())
                .build();
    }
}
