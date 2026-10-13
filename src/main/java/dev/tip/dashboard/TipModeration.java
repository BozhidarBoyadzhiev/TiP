package dev.tip.dashboard;

import dev.tip.owners.api.OwnerDirectory;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reading a hidden message and overruling the AI's flag. A verdict that changes something also changes the
 * owner's notes in the same transaction: unhiding marks today's digest stale, so the message is read on the
 * next run under the usual limits; hiding again drops today's digest and its request groups at once, so
 * the message cannot linger in the notes until the next run. The owner's pages are told after commit,
 * with the usual ids only messages, {@code digest} included, since the notes may now be rewritten.
 * Nothing reaches a device or an observer, and no model is called.
 */
@Component
class TipModeration {

    private static final Logger log = LoggerFactory.getLogger(TipModeration.class);

    private final ModerationRepository moderation;
    private final DigestRepository digests;
    private final OwnerDirectory owners;
    private final OwnerSockets sockets;
    private final TransactionTemplate transactions;
    private final Clock clock;

    TipModeration(
            ModerationRepository moderation,
            DigestRepository digests,
            OwnerDirectory owners,
            OwnerSockets sockets,
            TransactionTemplate transactions,
            Clock clock) {
        this.moderation = moderation;
        this.digests = digests;
        this.owners = owners;
        this.sockets = sockets;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The message of one of the owner's AI flagged tips, with its language. Empty for anything else. */
    Optional<ModerationRepository.Message> message(UUID ownerId, UUID tipId) {
        Optional<ModerationRepository.Message> message = moderation.message(ownerId, tipId);
        message.ifPresent(m -> log.info("Owner {} read the flagged message of tip {}", ownerId, tipId));
        return message;
    }

    /**
     * Hides the message again ({@code hidden} true) or overrules the flag (false). Repeating a verdict changes
     * nothing. False when the tip is not one of the owner's AI flagged tips.
     */
    boolean setHidden(UUID ownerId, UUID tipId, boolean hidden) {
        OwnerDay day = OwnerDay.of(clock, owners.timeZone(ownerId));
        Optional<Boolean> changed = transactions.execute(status -> moderation
                .setHidden(ownerId, tipId, hidden, day.instant())
                .map(change -> {
                    if (change.changed()) {
                        if (hidden) {
                            digests.drop(ownerId, day.today());
                        } else {
                            digests.markStale(ownerId, day.today());
                        }
                    }
                    return change.changed();
                }));
        if (changed == null || changed.isEmpty()) {
            return false;
        }
        if (changed.get()) {
            log.info("Owner {} {} tip {}", ownerId, hidden ? "hid again" : "unhid", tipId);
            sockets.todayChanged(ownerId);
            sockets.tipChanged(ownerId, tipId);
            // Either way the notes may now be rewritten, see DashboardController#digest.
            sockets.digestChanged(ownerId);
        }
        return true;
    }
}
