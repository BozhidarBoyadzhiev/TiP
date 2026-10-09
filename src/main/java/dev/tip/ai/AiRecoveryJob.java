package dev.tip.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 30 seconds, runs the classification flow again for captured tips it has not finished: a crash
 * between capture and classification, a worker that died holding a claim, or a failed attempt during an
 * API outage. The worker's claim keeps this safe while the capture listener runs at the same time.
 */
@Component
class AiRecoveryJob {

    static final int BATCH_SIZE = 50;

    private final AiTipRepository tips;
    private final TipReactionWorker worker;
    private final Clock clock;

    AiRecoveryJob(AiTipRepository tips, TipReactionWorker worker, Clock clock) {
        this.tips = tips;
        this.worker = worker;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tip.ai.recovery-interval}", initialDelayString = "${tip.ai.recovery-interval}")
    void recover() {
        Instant staleBefore = clock.instant().minus(TipReactionWorker.CLAIM_TIMEOUT);
        for (UUID tipId : tips.findUnfinished(staleBefore, BATCH_SIZE)) {
            worker.process(tipId);
            worker.failIfExhausted(tipId);
        }
    }
}
