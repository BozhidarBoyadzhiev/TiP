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
 * does nothing. Any failure rolls back the event row too, so PayPal's retry gets a clean attempt.
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
        boolean changed = switch (event.eventType()) {
            case PayPalWebhookEvent.CAPTURE_COMPLETED -> transitions.capture(tipId.get(), event.resourceId());
            case PayPalWebhookEvent.CAPTURE_PENDING -> transitions.pending(tipId.get(), event.resourceId());
            case PayPalWebhookEvent.CAPTURE_DENIED, PayPalWebhookEvent.CAPTURE_DECLINED ->
                transitions.fail(tipId.get());
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
     * By {@code custom_id} (our tip id), then the related order id, then the refunded capture id for a
     * refund, or the resource id as our capture id for a capture event.
     */
    private Optional<UUID> findTip(PayPalWebhookEvent event) {
        Optional<UUID> byCustomId = event.customTipId().filter(tips::existsById);
        if (byCustomId.isPresent()) {
            return byCustomId;
        }
        if (event.relatedOrderId() != null) {
            Optional<UUID> byOrder = tips.findIdByPaypalOrderId(event.relatedOrderId());
            if (byOrder.isPresent()) {
                return byOrder;
            }
        }
        Optional<UUID> byRefundedCapture =
                Optional.ofNullable(event.refundedCaptureId()).flatMap(tips::findIdByPaypalCaptureId);
        if (byRefundedCapture.isPresent() || event.eventType().equals(PayPalWebhookEvent.CAPTURE_REFUNDED)) {
            return byRefundedCapture;
        }
        return Optional.ofNullable(event.resourceId()).flatMap(tips::findIdByPaypalCaptureId);
    }
}
