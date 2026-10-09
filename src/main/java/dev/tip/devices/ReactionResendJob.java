package dev.tip.devices;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every second, sends unacknowledged reactions again to connected devices: 5 seconds after the last
 * send, up to 3 resends, while the reaction is under a minute old.
 */
@Component
class ReactionResendJob {

    private final ReactionDeliveryRepository deliveries;
    private final DeviceSessions sessions;
    private final ReactionSender sender;
    private final Clock clock;

    ReactionResendJob(
            ReactionDeliveryRepository deliveries, DeviceSessions sessions, ReactionSender sender, Clock clock) {
        this.deliveries = deliveries;
        this.sessions = sessions;
        this.sender = sender;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tip.devices.resend-interval}",
            initialDelayString = "${tip.devices.resend-interval}")
    void resend() {
        List<UUID> connected = sessions.connectedDevices();
        if (connected.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<PendingReaction> due = deliveries.dueForResend(
                connected,
                now,
                ReactionSender.RESEND_INTERVAL,
                ReactionSender.MAX_SENDS,
                now.minus(ReactionSender.MAX_AGE));
        for (PendingReaction reaction : due) {
            sessions.find(reaction.deviceId()).ifPresent(session -> sender.send(session, reaction));
        }
    }
}
