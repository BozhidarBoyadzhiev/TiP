package dev.tip.tipping;

import dev.tip.devices.api.DeviceDirectory;
import dev.tip.devices.api.DeviceSummary;
import dev.tip.paypal.api.CaptureResult;
import dev.tip.paypal.api.CreateOrderCommand;
import dev.tip.paypal.api.CreateOrderResult;
import dev.tip.paypal.api.PayPalGateway;
import dev.tip.shared.TipProperties;
import dev.tip.tipping.api.MessageSanitizer;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Creates and captures tips. PayPal is called outside any database transaction so no connection or row
 * lock is held during a network call.
 */
@Service
class TipService {

    private static final Logger log = LoggerFactory.getLogger(TipService.class);

    private final DeviceDirectory devices;
    private final TipRepository tips;
    private final TipTransitions transitions;
    private final PayPalGateway payPal;
    private final TipProperties properties;
    private final Clock clock;

    TipService(
            DeviceDirectory devices,
            TipRepository tips,
            TipTransitions transitions,
            PayPalGateway payPal,
            TipProperties properties,
            Clock clock) {
        this.devices = devices;
        this.tips = tips;
        this.transitions = transitions;
        this.payPal = payPal;
        this.properties = properties;
        this.clock = clock;
    }

    TipCreated create(String slug, long amountMinor, String currency, String rawMessage) {
        DeviceSummary device = devices.getTippableBySlug(slug);
        if (!properties.currency().equals(currency)) {
            throw new TipExceptions.InvalidTip(
                    "CURRENCY_NOT_SUPPORTED", "Tips are taken in " + properties.currency() + " only.");
        }
        if (amountMinor < properties.minMinor() || amountMinor > properties.maxMinor()) {
            throw new TipExceptions.InvalidTip("AMOUNT_OUT_OF_RANGE", "The tip amount is outside the allowed range.");
        }
        Tip tip = tips.save(new Tip(
                UUID.randomUUID(),
                device.id(),
                amountMinor,
                currency,
                MessageSanitizer.sanitize(rawMessage),
                clock.instant()));

        CreateOrderResult result = payPal.createOrder(
                new CreateOrderCommand(tip.id(), amountMinor, currency, "Tip for " + device.displayName()));
        return switch (result) {
            case CreateOrderResult.Created created -> {
                tips.attachOrder(tip.id(), created.orderId());
                yield new TipCreated(tip.id(), created.orderId());
            }
            case CreateOrderResult.Failed failed -> {
                transitions.fail(tip.id());
                log.warn("Tip {} failed, PayPal order not created: {}", tip.id(), failed.reason());
                throw new TipExceptions.PayPalUnavailable("PAYPAL_ORDER_FAILED");
            }
        };
    }

    TipStatus capture(UUID tipId) {
        Tip tip = tips.findById(tipId).orElseThrow(TipExceptions.TipNotFound::new);
        if (tip.status().closed()) {
            throw new TipExceptions.TipClosed();
        }
        if (!tip.status().capturable()) {
            return tip.status();
        }
        if (tip.paypalOrderId() == null) {
            throw new TipExceptions.TipNotReady();
        }
        CaptureResult result = payPal.captureOrder(tipId, tip.paypalOrderId());
        if (result instanceof CaptureResult.AlreadyCaptured) {
            result = payPal.lookupOrder(tip.paypalOrderId());
        }
        apply(tipId, result);
        // The tip may be gone: an expired sandbox is deleted with its tips while PayPal is called.
        return tips.findStatus(tipId).orElseThrow(TipExceptions.TipNotFound::new);
    }

    private void apply(UUID tipId, CaptureResult result) {
        switch (result) {
            case CaptureResult.Completed completed -> transitions.capture(tipId, completed.captureId());
            case CaptureResult.Pending pending -> transitions.pending(tipId, pending.captureId());
            case CaptureResult.Declined declined -> throw new TipExceptions.InstrumentDeclined();
            case CaptureResult.OrderNotFound notFound -> {
                transitions.fail(tipId);
                log.warn("Tip {} failed, PayPal does not know its order", tipId);
                throw new TipExceptions.PayPalUnavailable("PAYPAL_ORDER_NOT_FOUND");
            }
            case CaptureResult.AlreadyCaptured already -> {
                log.warn("Tip {}: PayPal reports the order captured but the order shows no capture", tipId);
                throw new TipExceptions.PayPalUnavailable("PAYPAL_CAPTURE_FAILED");
            }
            case CaptureResult.Failed failed -> {
                log.warn("Tip {} capture failed: {}", tipId, failed.reason());
                throw new TipExceptions.PayPalUnavailable("PAYPAL_CAPTURE_FAILED");
            }
        }
    }

    record TipCreated(UUID tipId, String paypalOrderId) {}
}
