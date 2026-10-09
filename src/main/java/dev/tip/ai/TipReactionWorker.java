package dev.tip.ai;

import dev.tip.ai.MessageClassifier.ClassificationFailed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the classification flow for one tip: claim, classify or skip, then finish. Safe to call from any
 * number of threads for the same tip; the conditional updates in {@link AiTipRepository} let exactly one
 * of them finish it. Claude is called outside any transaction.
 */
@Component
class TipReactionWorker {

    static final int MAX_ATTEMPTS = 3;

    /** A claim older than this is presumed dead (crashed or hung worker) and can be taken over. */
    static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(TipReactionWorker.class);

    private final AiTipRepository tips;
    private final TipReactions reactions;
    private final Optional<MessageClassifier> classifier;
    private final Clock clock;

    TipReactionWorker(
            AiTipRepository tips, TipReactions reactions, Optional<MessageClassifier> classifier, Clock clock) {
        this.tips = tips;
        this.reactions = reactions;
        this.classifier = classifier;
        this.clock = clock;
    }

    void process(UUID tipId) {
        Instant claimedAt = now();
        Optional<AiTipRepository.Claim> claim = tips.claim(tipId, claimedAt, claimedAt.minus(CLAIM_TIMEOUT));
        if (claim.isEmpty()) {
            return;
        }
        AiTipRepository.Claim work = claim.get();
        TipReactions.Outcome outcome;
        if (classifier.isEmpty() || work.message() == null) {
            outcome = TipReactions.Outcome.skipped(work.amountMinor(), work.currency());
        } else {
            try {
                outcome = TipReactions.Outcome.classified(
                        classifier.get().classify(work.message()), work.amountMinor(), work.currency());
            } catch (RuntimeException e) {
                String reason = e instanceof ClassificationFailed
                        ? e.getMessage()
                        : e.getClass().getSimpleName();
                if (work.attempts() < MAX_ATTEMPTS) {
                    tips.release(tipId, claimedAt);
                    log.warn("Classifying tip {} failed on attempt {}, will retry: {}", tipId, work.attempts(), reason);
                    return;
                }
                log.warn("Classifying tip {} failed on the last attempt, reacting PLAIN: {}", tipId, reason);
                outcome = TipReactions.Outcome.failed(work.amountMinor(), work.currency());
            }
        }
        if (reactions.finish(tipId, work.deviceId(), claimedAt, outcome, now())) {
            log.info(
                    "Tip {} reacts {} x{} (kind {}, ai {}, flagged {})",
                    tipId,
                    outcome.reaction(),
                    outcome.repeats(),
                    outcome.kind(),
                    outcome.status(),
                    outcome.flagged());
        } else {
            log.info("Tip {}: result discarded, another worker took over the expired claim", tipId);
        }
    }

    /** See {@link AiTipRepository#failExhausted}. */
    void failIfExhausted(UUID tipId) {
        Instant now = now();
        if (reactions.failExhausted(tipId, now.minus(CLAIM_TIMEOUT), now)) {
            log.warn("Tip {} ran out of classification attempts, reacting PLAIN", tipId);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
