package dev.tip.shared;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In memory token buckets per client IP for one endpoint: {@code capacity} requests, refilled evenly over {@code
 * period}. The client IP is the one Tomcat resolved from the configured header ({@code getRemoteAddr()}). A
 * refusal logs one WARN {@code rate_limited} line with the endpoint and the hashed IP ({@link LogSafe}). {@link
 * #byKey} keys the buckets by something else, such as an owner id. Idle buckets expire; single instance only, like
 * the rest of the backend.
 */
public final class ClientRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ClientRateLimiter.class);

    private static final Duration MIN_IDLE = Duration.ofMinutes(5);

    private final String endpoint;
    private final int capacity;
    private final Duration period;
    private final String keyName;
    private final UnaryOperator<String> loggedKey;
    private final Cache<String, Bucket> buckets;

    public ClientRateLimiter(String endpoint, int capacity, Duration period) {
        this(endpoint, capacity, period, "ip", LogSafe::ip);
    }

    private ClientRateLimiter(
            String endpoint, int capacity, Duration period, String keyName, UnaryOperator<String> loggedKey) {
        this.endpoint = endpoint;
        this.capacity = capacity;
        this.period = period;
        this.keyName = keyName;
        this.loggedKey = loggedKey;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(period.compareTo(MIN_IDLE) > 0 ? period : MIN_IDLE)
                .maximumSize(100_000)
                .build();
    }

    /**
     * Buckets keyed by something that is not an IP and is safe to log as it is, such as an owner id; a refusal logs
     * {@code <keyName>=<key>}.
     */
    public static ClientRateLimiter byKey(String endpoint, int capacity, Duration period, String keyName) {
        return new ClientRateLimiter(endpoint, capacity, period, keyName, UnaryOperator.identity());
    }

    /** Takes one request from the key's bucket, the client IP unless made {@link #byKey}. False, with the WARN line, when it is empty. */
    public boolean tryAcquire(String key) {
        if (buckets.get(key, k -> newBucket()).tryConsume(1)) {
            return true;
        }
        log.warn("rate_limited endpoint={} {}={}", endpoint, keyName, loggedKey.apply(key));
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
