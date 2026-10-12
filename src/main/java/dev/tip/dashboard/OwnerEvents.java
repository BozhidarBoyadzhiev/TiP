package dev.tip.dashboard;

import dev.tip.ai.api.TipReacted;
import dev.tip.devices.api.DeviceStatusChanged;
import dev.tip.owners.api.OwnerDirectory;
import dev.tip.owners.api.OwnerSignedOut;
import dev.tip.tipping.api.TipCaptured;
import dev.tip.tipping.api.TipRefunded;
import dev.tip.tipping.api.TipReversed;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns other features' events into owner channel messages, and marks the owner's digest stale on every
 * reaction. Every message goes only to the sockets of the tip's or device's own owner. Listeners that read
 * the database run after commit on the dashboard executor.
 */
@Component
class OwnerEvents {

    private final DashboardRepository tips;
    private final DigestRepository digests;
    private final OwnerDirectory owners;
    private final OwnerSockets sockets;

    OwnerEvents(DashboardRepository tips, DigestRepository digests, OwnerDirectory owners, OwnerSockets sockets) {
        this.tips = tips;
        this.digests = digests;
        this.owners = owners;
        this.sockets = sockets;
    }

    @Async(DashboardConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipCaptured event) {
        tipChanged(event.deviceId(), event.tipId());
    }

    /** Classified, skipped or failed: the tip's kind and the request count may have changed. */
    @Async(DashboardConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipReacted event) {
        tips.ownerOfDevice(event.deviceId()).ifPresent(ownerId -> {
            digests.markStale(ownerId, LocalDate.ofInstant(event.reactedAt(), owners.timeZone(ownerId)));
            sockets.tipChanged(ownerId, event.tipId());
            sockets.todayChanged(ownerId);
        });
    }

    @Async(DashboardConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipRefunded event) {
        tipChanged(event.deviceId(), event.tipId());
    }

    @Async(DashboardConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipReversed event) {
        tipChanged(event.deviceId(), event.tipId());
    }

    @EventListener
    void on(DeviceStatusChanged event) {
        sockets.deviceChanged(event.ownerId(), event.slug(), event.online(), event.lastSeenAt());
    }

    @EventListener
    void on(OwnerSignedOut event) {
        sockets.closeAll(event.ownerId(), "signed_out", OwnerSockets.SIGNED_OUT);
    }

    private void tipChanged(UUID deviceId, UUID tipId) {
        tips.ownerOfDevice(deviceId).ifPresent(ownerId -> {
            sockets.tipChanged(ownerId, tipId);
            sockets.todayChanged(ownerId);
        });
    }
}
