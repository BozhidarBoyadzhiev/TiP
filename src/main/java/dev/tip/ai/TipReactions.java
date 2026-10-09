package dev.tip.ai;

import dev.tip.ai.api.Reaction;
import dev.tip.ai.api.TipReacted;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Final writes. Only the write that changes the row publishes {@link TipReacted}, delivered after commit. */
@Component
class TipReactions {

    private final AiTipRepository tips;
    private final ApplicationEventPublisher events;

    TipReactions(AiTipRepository tips, ApplicationEventPublisher events) {
        this.tips = tips;
        this.events = events;
    }

    @Transactional
    public boolean finish(UUID tipId, UUID deviceId, Instant claimedAt, Outcome outcome, Instant reactedAt) {
        int changed = tips.finish(
                tipId,
                claimedAt,
                outcome.reaction(),
                outcome.flagged(),
                outcome.flagReason(),
                outcome.status(),
                reactedAt);
        if (changed != 1) {
            return false;
        }
        events.publishEvent(new TipReacted(tipId, deviceId, outcome.reaction(), reactedAt));
        return true;
    }

    @Transactional
    public boolean failExhausted(UUID tipId, Instant staleBefore, Instant reactedAt) {
        return tips.failExhausted(tipId, staleBefore, reactedAt)
                .map(deviceId -> {
                    events.publishEvent(new TipReacted(tipId, deviceId, Reaction.NEUTRAL, reactedAt));
                    return true;
                })
                .orElse(false);
    }

    record Outcome(Reaction reaction, boolean flagged, String flagReason, AiStatus status) {

        static Outcome classified(MessageClassifier.Classification classification) {
            return new Outcome(
                    classification.reaction(), classification.flagged(), classification.flagReason(), AiStatus.DONE);
        }

        static Outcome skipped() {
            return new Outcome(Reaction.NEUTRAL, false, null, AiStatus.SKIPPED);
        }

        static Outcome failed() {
            return new Outcome(Reaction.NEUTRAL, false, null, AiStatus.FAILED);
        }
    }
}
