package dev.tip.tipping;

import dev.tip.tipping.api.TipCaptured;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Placeholder consumer until the AI and device features listen. Never logs the message text. */
@Component
class TipCapturedLogger {

    private static final Logger log = LoggerFactory.getLogger(TipCapturedLogger.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipCaptured event) {
        log.info("Tip {} captured for device {} at {}", event.tipId(), event.deviceId(), event.capturedAt());
    }
}
