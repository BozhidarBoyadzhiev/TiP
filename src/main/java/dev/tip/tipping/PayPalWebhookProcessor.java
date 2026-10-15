package dev.tip.tipping;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a verified webhook event and applies its transition in one transaction. A duplicate event id
 * does nothing. Any failure rolls back the event row too, so PayPal's retry gets a clean attempt. A refund or
 * reversal of a tip whose capture is not recorded yet is such a failure on purpose: its retry comes after the
 * capture, and a late capture can no longer leave a refunded tip showing as captured.
 */
@Component
class PayPalWebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookProcessor.class);

    private final JdbcClient jdbc;
    private final TipRepository tips;
    private final TipTransitions transitions;
    private final Clock clock;

    PayPalWebhookProcessor(JdbcClient jdbc, TipRepository tips, TipTransitions transitions, Clock clock) {
        this.jdbc = jdbc;
        this.tips = tips;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Transactional
    public void process(PayPalWebhookEvent event) {
        if (!recordFirstDelivery(event)) {
            log.info("PayPal webhook {} already processed", event.eventId());
            return;
        }
        if (!isHandled(event.eventType())) {
            log.info("PayPal webhook {} of type {} stored without action", event.eventId(), event.eventType());
            return;
        }
        Optional<UUID> tipId = findTip(event);
        if (tipId.isEmpty()) {
            log.warn("PayPal webhook {} of type {} matches no tip", event.eventId(), event.eventType());
            return;
        }
        if (undoesACaptureNotYetRecorded(event, tipId.get())) {
            log.info(
                    "PayPal webhook {} of type {} for tip {} came before its capture, PayPal will retry",
                    event.eventId(),
                    event.eventType(),
                    tipId.get());
            throw new TipExceptions.WebhookBeforeCapture();
        }
        if (!chargesTheTip(event, tipId.get())) {
            log.warn(
                    "PayPal webhook {} of type {} for tip {} does not charge the tip's amount, left the tip unchanged",
                    event.eventId(),
                    event.eventType(),
                    tipId.get());
            return;
        }
        boolean changed = switch (event.eventType()) {
            case PayPalWebhookEvent.CAPTURE_COMPLETED -> transitions.capture(tipId.get(), event.resourceId());
            case PayPalWebhookEvent.CAPTURE_PENDING -> transitions.pending(tipId.get(), event.resourceId());
            case PayPalWebhookEvent.CAPTURE_DENIED, PayPalWebhookEvent.CAPTURE_DECLINED ->
                transitions.failPending(tipId.get());
            case PayPalWebhookEvent.CAPTURE_REFUNDED -> transitions.refund(tipId.get());
            case PayPalWebhookEvent.CAPTURE_REVERSED -> transitions.reverse(tipId.get());
            default -> false;
        };
        log.info(
                "PayPal webhook {} of type {} for tip {} {}",
                event.eventId(),
                event.eventType(),
                tipId.get(),
                changed ? "applied" : "left the tip unchanged");
    }

    private boolean recordFirstDelivery(PayPalWebhookEvent event) {
        return jdbc.sql("INSERT INTO paypal_webhook_event (event_id, event_type, resource_id, received_at)"
                                + " VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING")
                        .params(event.eventId(), event.eventType(), event.resourceId(), Timestamp.from(clock.instant()))
                        .update()
                == 1;
    }

    private static boolean isHandled(String eventType) {
        return switch (eventType) {
            case PayPalWebhookEvent.CAPTURE_COMPLETED,
                    PayPalWebhookEvent.CAPTURE_PENDING,
                    PayPalWebhookEvent.CAPTURE_DENIED,
                    PayPalWebhookEvent.CAPTURE_DECLINED,
                    PayPalWebhookEvent.CAPTURE_REFUNDED,
                    PayPalWebhookEvent.CAPTURE_REVERSED -> true;
            default -> false;
        };
    }

    /**
     * Only through ids TiP stored itself, never the resource's {@code custom_id}: a refund by the capture it
     * refunded; a capture event by its order, then by its own capture id, stored by an earlier pending
     * capture. An order someone else created on our account matches no tip.
     */
    private Optional<UUID> findTip(PayPalWebhookEvent event) {
        if (event.eventType().equals(PayPalWebhookEvent.CAPTURE_REFUNDED)) {
            return Optional.ofNullable(event.refundedCaptureId()).flatMap(tips::findIdByPaypalCaptureId);
        }
        if (event.relatedOrderId() != null) {
            Optional<UUID> byOrder = tips.findIdByPaypalOrderId(event.relatedOrderId());
            if (byOrder.isPresent()) {
                return byOrder;
            }
        }
        return Optional.ofNullable(event.resourceId()).flatMap(tips::findIdByPaypalCaptureId);
    }

    private boolean undoesACaptureNotYetRecorded(PayPalWebhookEvent event, UUID tipId) {
        if (!event.eventType().equals(PayPalWebhookEvent.CAPTURE_REFUNDED)
                && !event.eventType().equals(PayPalWebhookEvent.CAPTURE_REVERSED)) {
            return false;
        }
        return tips.findStatus(tipId).map(TipStatus::capturable).orElse(false);
    }

    /** A completed or pending capture counts only for exactly the tip's amount and currency. */
    private boolean chargesTheTip(PayPalWebhookEvent event, UUID tipId) {
        if (!event.eventType().equals(PayPalWebhookEvent.CAPTURE_COMPLETED)
                && !event.eventType().equals(PayPalWebhookEvent.CAPTURE_PENDING)) {
            return true;
        }
        return tips.findById(tipId)
                .map(tip -> event.amountEquals(tip.amountMinor(), tip.currency()))
                .orElse(false);
    }
}
