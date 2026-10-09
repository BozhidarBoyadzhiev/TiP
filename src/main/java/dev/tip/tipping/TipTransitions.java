package dev.tip.tipping;

import dev.tip.tipping.api.TipCaptured;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The only place tip status changes. Joins the caller's transaction when there is one. */
@Component
class TipTransitions {

    private final TipRepository tips;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TipTransitions(TipRepository tips, ApplicationEventPublisher events, Clock clock) {
        this.tips = tips;
        this.events = events;
        this.clock = clock;
    }

    /** Moves CREATED or PENDING to CAPTURED. Only the caller that wins publishes {@link TipCaptured}. */
    @Transactional
    public boolean capture(UUID tipId, String captureId) {
        Instant capturedAt = clock.instant();
        if (tips.markCaptured(tipId, captureId, capturedAt) != 1) {
            return false;
        }
        UUID deviceId = tips.findDeviceId(tipId).orElseThrow();
        events.publishEvent(new TipCaptured(tipId, deviceId, capturedAt));
        return true;
    }

    @Transactional
    public boolean pending(UUID tipId, String captureId) {
        return tips.markPending(tipId, captureId) == 1;
    }

    @Transactional
    public boolean fail(UUID tipId) {
        return tips.markFailed(tipId) == 1;
    }

    @Transactional
    public boolean refund(UUID tipId) {
        return tips.markRefunded(tipId) == 1;
    }
}
