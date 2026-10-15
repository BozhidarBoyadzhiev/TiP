package dev.tip.devices;

import dev.tip.ai.api.TipReacted;
import dev.tip.shared.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers each committed {@link TipReacted}: stores it, sends it to the device when connected, and
 * broadcasts it to the device's observers. The insert is the duplicate check, so a repeated event
 * neither stores nor sends twice. A device that is offline gets the reaction on its next connect. A
 * reaction the device must not play (a tip PayPal confirmed after it was failed or abandoned) is neither
 * stored nor sent nor broadcast. Runs on the delivery executor, see {@link WebSocketConfig#deliveryExecutor()}.
 */
@Component
class ReactionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReactionDispatcher.class);

    private final ReactionDeliveryRepository deliveries;
    private final DeviceSessions sessions;
    private final ReactionSender sender;
    private final ObserverSessions observers;
    private final DeviceRepository devices;
    private final ChannelMessages messages;

    ReactionDispatcher(
            ReactionDeliveryRepository deliveries,
            DeviceSessions sessions,
            ReactionSender sender,
            ObserverSessions observers,
            DeviceRepository devices,
            ChannelMessages messages) {
        this.deliveries = deliveries;
        this.sessions = sessions;
        this.sender = sender;
        this.observers = observers;
        this.devices = devices;
        this.messages = messages;
    }

    @Async(WebSocketConfig.DELIVERY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipReacted event) {
        if (!event.playOnDevice()) {
            log.info("Tip {} was confirmed after it was closed, not played on the device", event.tipId());
            return;
        }
        PendingReaction reaction = new PendingReaction(
                event.tipId(), event.deviceId(), event.reaction(), event.repeats(), event.reactedAt());
        try {
            if (!deliveries.insert(reaction)) {
                log.debug("Ignored a duplicate reaction for tip {}", event.tipId());
                return;
            }
        } catch (RuntimeException e) {
            log.error("Could not store the reaction for tip {}: {}", event.tipId(), LogSafe.describe(e));
            return;
        }
        sessions.find(event.deviceId()).ifPresent(session -> sender.send(session, reaction));
        if (!observers.isEmpty()) {
            devices.findById(event.deviceId())
                    .ifPresent(device -> observers.broadcast(
                            device.slug(),
                            messages.observerReaction(event.reaction(), event.repeats(), event.reactedAt())));
        }
    }
}
