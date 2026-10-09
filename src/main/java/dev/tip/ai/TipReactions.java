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
        if (tips.finish(tipId, claimedAt, outcome, reactedAt) != 1) {
            return false;
        }
        events.publishEvent(new TipReacted(tipId, deviceId, outcome.reaction(), reactedAt));
        return true;
    }

    @Transactional
    public boolean failExhausted(UUID tipId, Instant staleBefore, Instant reactedAt) {
        return tips.failExhausted(tipId, staleBefore, reactedAt)
                .map(deviceId -> {
                    events.publishEvent(new TipReacted(tipId, deviceId, Reaction.PLAIN, reactedAt));
                    return true;
                })
                .orElse(false);
    }

    /**
     * What gets stored. {@code kind} is null when the model never read the message. A flagged message
     * plays PLAIN and keeps no owner note, whatever its kind.
     */
    record Outcome(
            MessageKind kind,
            Reaction reaction,
            boolean flagged,
            String flagReason,
            String ownerNote,
            AiStatus status) {

        static Outcome classified(MessageClassifier.Classification classification) {
            boolean flagged = classification.flagged();
            return new Outcome(
                    classification.kind(),
                    flagged ? Reaction.PLAIN : classification.kind().reaction(),
                    flagged,
                    classification.flagReason(),
                    flagged ? null : classification.ownerNote(),
                    AiStatus.DONE);
        }

        static Outcome skipped() {
            return new Outcome(null, Reaction.PLAIN, false, null, null, AiStatus.SKIPPED);
        }

        static Outcome failed() {
            return new Outcome(null, Reaction.PLAIN, false, null, null, AiStatus.FAILED);
        }
    }
}
