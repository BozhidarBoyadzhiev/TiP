package dev.tip.devices;

import dev.tip.ai.api.TipReacted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers each committed {@link TipReacted}: stores it, sends it to the device when connected, and
 * broadcasts it to the device's observers. The insert is the duplicate check, so a repeated event
 * neither stores nor sends twice. A device that is offline gets the reaction on its next connect. Runs
 * on the delivery executor, see {@link WebSocketConfig#deliveryExecutor()}.
 */
@Component
class ReactionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReactionDispatcher.class);

    private final ReactionDeliveryRepository deliveries;
    private final DeviceSessions sessions;
    private final ReactionSender sender;

    ReactionDispatcher(ReactionDeliveryRepository deliveries, DeviceSessions sessions, ReactionSender sender) {
        this.deliveries = deliveries;
        this.sessions = sessions;
        this.sender = sender;
    }

    @Async(WebSocketConfig.DELIVERY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipReacted event) {
        PendingReaction reaction =
                new PendingReaction(event.tipId(), event.deviceId(), event.reaction(), event.reactedAt());
        try {
            if (!deliveries.insert(reaction)) {
                log.debug("Ignored a duplicate reaction for tip {}", event.tipId());
                return;
            }
        } catch (RuntimeException e) {
            log.error("Could not store the reaction for tip {}", event.tipId(), e);
            return;
        }
        sessions.find(event.deviceId()).ifPresent(session -> sender.send(session, reaction));
    }
}
