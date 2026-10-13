package dev.tip.tipping;

import dev.tip.devices.api.DeviceDirectory;
import dev.tip.paypal.api.PayPalGateway;
import dev.tip.paypal.api.RefundResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owner initiated full refunds. PayPal is called outside any transaction. A completed refund moves the
 * tip to REFUNDED through the same conditional update the {@code PAYMENT.CAPTURE.REFUNDED} webhook uses,
 * so whichever arrives first makes the one transition.
 */
@Service
class TipRefundService {

    private static final Logger log = LoggerFactory.getLogger(TipRefundService.class);

    private final TipRepository tips;
    private final TipTransitions transitions;
    private final DeviceDirectory devices;
    private final PayPalGateway payPal;

    TipRefundService(TipRepository tips, TipTransitions transitions, DeviceDirectory devices, PayPalGateway payPal) {
        this.tips = tips;
        this.transitions = transitions;
        this.devices = devices;
        this.payPal = payPal;
    }

    /** A tip of another owner is reported as not found, so tip ids cannot be probed. */
    RefundOutcome refund(UUID tipId, UUID ownerId) {
        Tip tip = tips.findById(tipId)
                .filter(t -> devices.isOwnedBy(t.deviceId(), ownerId))
                .orElseThrow(TipExceptions.TipNotFound::new);
        if (tip.demo()) {
            throw new TipExceptions.DemoTip();
        }
        if (tip.status() != TipStatus.CAPTURED || tip.paypalCaptureId() == null) {
            throw new TipExceptions.TipNotRefundable();
        }
        RefundResult result = payPal.refundCapture(tipId, tip.paypalCaptureId());
        return switch (result) {
            case RefundResult.Completed completed -> completed(tipId, ownerId);
            case RefundResult.AlreadyRefunded already -> completed(tipId, ownerId);
            case RefundResult.Pending pending -> {
                log.info("Refund of tip {} by owner {} is pending at PayPal", tipId, ownerId);
                yield new RefundOutcome(currentStatus(tipId), false);
            }
            case RefundResult.Failed failed -> {
                log.warn("Refund of tip {} by owner {} failed: {}", tipId, ownerId, failed.reason());
                throw new TipExceptions.PayPalUnavailable("PAYPAL_REFUND_FAILED");
            }
        };
    }

    private RefundOutcome completed(UUID tipId, UUID ownerId) {
        transitions.refund(tipId);
        log.info("Tip {} refunded by owner {}", tipId, ownerId);
        return new RefundOutcome(currentStatus(tipId), true);
    }

    private TipStatus currentStatus(UUID tipId) {
        return tips.findStatus(tipId).orElseThrow(TipExceptions.TipNotFound::new);
    }

    /** {@code completed} is false while PayPal still processes the refund. */
    record RefundOutcome(TipStatus status, boolean completed) {}
}
