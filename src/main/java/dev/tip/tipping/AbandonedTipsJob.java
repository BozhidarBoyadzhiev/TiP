package dev.tip.tipping;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Tips still in CREATED after six hours were never paid. Marks them ABANDONED every ten minutes. */
@Component
class AbandonedTipsJob {

    static final Duration MAX_AGE = Duration.ofHours(6);

    private static final Logger log = LoggerFactory.getLogger(AbandonedTipsJob.class);

    private final TipRepository tips;
    private final Clock clock;

    AbandonedTipsJob(TipRepository tips, Clock clock) {
        this.tips = tips;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    void abandonStaleTips() {
        int abandoned = tips.abandonCreatedBefore(clock.instant().minus(MAX_AGE));
        if (abandoned > 0) {
            log.info("Marked {} unpaid tips as abandoned", abandoned);
        }
    }
}
