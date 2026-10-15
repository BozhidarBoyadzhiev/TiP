package dev.tip.tipping;

import dev.tip.paypal.api.CaptureResult;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A PENDING tip waits for PayPal's final webhook, which can be lost. Every {@code tip.tipping.reconcile-interval}
 * (1 hour) the tips created more than {@link #MIN_AGE} ago and still pending are looked up at PayPal, {@link #BATCH}
 * a run, oldest first: a completed capture records the tip, a declined one fails it, and a capture still pending or
 * a failed lookup leaves it for the next run.
 */
@Component
class PendingTipsJob {

    static final Duration MIN_AGE = Duration.ofHours(1);

    static final int BATCH = 20;

    private static final Logger log = LoggerFactory.getLogger(PendingTipsJob.class);

    private final TipRepository tips;
    private final TipTransitions transitions;
    private final OrderLookup lookup;
    private final Clock clock;

    PendingTipsJob(TipRepository tips, TipTransitions transitions, OrderLookup lookup, Clock clock) {
        this.tips = tips;
        this.transitions = transitions;
        this.lookup = lookup;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tip.tipping.reconcile-interval}",
            initialDelayString = "${tip.tipping.reconcile-interval}")
    void reconcile() {
        for (Tip tip : tips.findPendingCreatedBefore(clock.instant().minus(MIN_AGE), BATCH)) {
            switch (lookup.lookup(tip)) {
                case CaptureResult.Completed completed -> {
                    if (transitions.capture(tip.id(), completed.captureId())) {
                        log.info("Pending tip {} was completed at PayPal, recorded", tip.id());
                    }
                }
                case CaptureResult.Declined declined -> {
                    if (transitions.failPending(tip.id())) {
                        log.info("Pending tip {} was declined at PayPal, failed", tip.id());
                    }
                }
                default -> log.debug("Pending tip {} is still unsettled at PayPal", tip.id());
            }
        }
    }
}
