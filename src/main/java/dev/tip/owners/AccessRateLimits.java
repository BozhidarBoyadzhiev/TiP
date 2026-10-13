package dev.tip.owners;

import dev.tip.shared.ClientRateLimiter;
import dev.tip.shared.RateLimitProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Per client IP limits on the sign ins without a password, sized from {@link RateLimitProperties}. */
@Component
class AccessRateLimits {

    private final ClientRateLimiter judgeSessions;
    private final ClientRateLimiter demoSessions;
    private final ClientRateLimiter resumes;

    AccessRateLimits(RateLimitProperties limits) {
        judgeSessions = new ClientRateLimiter("judges_session", limits.judgeSessionsPerHour(), Duration.ofHours(1));
        demoSessions = new ClientRateLimiter("demo_session", limits.demoSessionsPerMinute(), Duration.ofMinutes(1));
        resumes = new ClientRateLimiter("judges_resume", limits.resumesPerMinute(), Duration.ofMinutes(1));
    }

    boolean judgeSession(String clientIp) {
        return judgeSessions.tryAcquire(clientIp);
    }

    boolean demoSession(String clientIp) {
        return demoSessions.tryAcquire(clientIp);
    }

    boolean resume(String clientIp) {
        return resumes.tryAcquire(clientIp);
    }
}
