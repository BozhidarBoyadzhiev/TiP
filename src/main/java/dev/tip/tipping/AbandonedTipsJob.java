package dev.tip.tipping;

import dev.tip.paypal.api.CaptureResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tips still in CREATED after six hours were never paid, or their capture answer and webhook were both lost. Every
 * {@code tip.tipping.abandon-interval} (10 minutes) a tip whose capture was never attempted is marked ABANDONED
 * without asking PayPal, since its order was never approved. A tip whose capture was attempted is looked up first,
 * {@link #BATCH} a run: a capture PayPal confirms records it, a pending one pends it, anything else abandons it. A
 * capture PayPal confirms even later still records an abandoned tip.
 */
@Component
class AbandonedTipsJob {

    static final Duration MAX_AGE = Duration.ofHours(6);

    static final int BATCH = 20;

    private static final Logger log = LoggerFactory.getLogger(AbandonedTipsJob.class);

    private final TipRepository tips;
    private final TipTransitions transitions;
    private final OrderLookup lookup;
    private final Clock clock;

    AbandonedTipsJob(TipRepository tips, TipTransitions transitions, OrderLookup lookup, Clock clock) {
        this.tips = tips;
        this.transitions = transitions;
        this.lookup = lookup;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tip.tipping.abandon-interval}",
            initialDelayString = "${tip.tipping.abandon-interval}")
    void abandonStaleTips() {
        Instant cutoff = clock.instant().minus(MAX_AGE);
        int abandoned = tips.abandonUnattemptedCreatedBefore(cutoff);
        for (Tip tip : tips.findAttemptedCreatedBefore(cutoff, BATCH)) {
            switch (lookup.lookup(tip)) {
                case CaptureResult.Completed completed -> {
                    if (transitions.capture(tip.id(), completed.captureId())) {
                        log.info("Tip {} was captured at PayPal, recorded instead of abandoned", tip.id());
                    }
                }
                case CaptureResult.Pending pending -> transitions.pending(tip.id(), pending.captureId());
                default -> {
                    if (transitions.abandon(tip.id())) {
                        abandoned++;
                    }
                }
            }
        }
        if (abandoned > 0) {
            log.info("Marked {} unpaid tips as abandoned", abandoned);
        }
    }
}
