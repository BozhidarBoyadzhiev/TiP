package dev.tip.ai;

import dev.tip.ai.api.Reaction;
import dev.tip.ai.api.ReactionRepeats;
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
        events.publishEvent(new TipReacted(tipId, deviceId, outcome.reaction(), outcome.repeats(), reactedAt));
        return true;
    }

    @Transactional
    public boolean failExhausted(UUID tipId, Instant staleBefore, Instant reactedAt) {
        return tips.failExhausted(tipId, staleBefore, reactedAt)
                .map(tip -> {
                    Outcome outcome = Outcome.failed(tip.amountMinor(), tip.currency());
                    events.publishEvent(
                            new TipReacted(tipId, tip.deviceId(), outcome.reaction(), outcome.repeats(), reactedAt));
                    return true;
                })
                .orElse(false);
    }

    /**
     * What gets stored and delivered. {@code kind} is null when the model never read the message. A
     * flagged message plays PLAIN once, whatever its kind and amount, and still keeps the model's whole read
     * (kind, owner note, language, translation, song), so the owner can overrule the flag later without
     * another call; the dashboard decides when the owner sees it. Every other outcome repeats by the amount,
     * see {@link ReactionRepeats}. {@code songKey} is computed here from the song, see {@link SongKey}. None
     * of the texts ever reaches a device.
     */
    record Outcome(
            MessageKind kind,
            Reaction reaction,
            int repeats,
            boolean flagged,
            String flagReason,
            String ownerNote,
            String language,
            String englishText,
            String song,
            String songKey,
            AiStatus status) {

        static Outcome classified(MessageClassifier.Classification classification, long amountMinor, String currency) {
            boolean flagged = classification.flagged();
            return new Outcome(
                    classification.kind(),
                    flagged ? Reaction.PLAIN : classification.kind().reaction(),
                    ReactionRepeats.of(amountMinor, currency, flagged),
                    flagged,
                    classification.flagReason(),
                    classification.ownerNote(),
                    classification.language(),
                    classification.englishText(),
                    classification.song(),
                    SongKey.of(classification.song()),
                    AiStatus.DONE);
        }

        static Outcome skipped(long amountMinor, String currency) {
            return new Outcome(
                    null,
                    Reaction.PLAIN,
                    ReactionRepeats.of(amountMinor, currency, false),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    AiStatus.SKIPPED);
        }

        static Outcome failed(long amountMinor, String currency) {
            return new Outcome(
                    null,
                    Reaction.PLAIN,
                    ReactionRepeats.of(amountMinor, currency, false),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    AiStatus.FAILED);
        }
    }
}
