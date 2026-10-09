package dev.tip.tipping;

import dev.tip.owners.api.OwnerPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner session and CSRF token required, see the owner security chain. */
@RestController
class TipRefundController {

    private final TipRefundService refunds;

    TipRefundController(TipRefundService refunds) {
        this.refunds = refunds;
    }

    /** 200 when the refund completed, 202 while PayPal still processes it and the webhook will finish it. */
    @PostMapping("/api/owner/tips/{tipId}/refund")
    ResponseEntity<RefundResponse> refund(@PathVariable UUID tipId, @AuthenticationPrincipal OwnerPrincipal owner) {
        TipRefundService.RefundOutcome outcome = refunds.refund(tipId, owner.id());
        return ResponseEntity.status(outcome.completed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(new RefundResponse(outcome.status(), outcome.completed() ? "COMPLETED" : "PENDING"));
    }

    record RefundResponse(TipStatus status, String refundStatus) {}
}
