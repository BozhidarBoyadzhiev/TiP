package dev.tip.paypal;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.exceptions.ErrorException;
import com.paypal.sdk.models.ErrorDetails;
import com.paypal.sdk.models.Refund;
import com.paypal.sdk.models.RefundCapturedPaymentInput;
import com.paypal.sdk.models.RefundRequest;
import dev.tip.paypal.api.RefundResult;
import io.apimatic.core.exceptions.AuthValidationException;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Capture refunds through the PayPal Server SDK. A request body without an amount refunds in full. */
@Component
class PayPalRefunds {

    private static final Logger log = LoggerFactory.getLogger(PayPalRefunds.class);
    private static final String JSON = "application/json";
    private static final String RETURN_REPRESENTATION = "return=representation";

    private final PaypalServerSdkClient client;

    PayPalRefunds(PaypalServerSdkClient client) {
        this.client = client;
    }

    RefundResult refund(UUID tipId, String captureId) {
        RefundCapturedPaymentInput input = new RefundCapturedPaymentInput.Builder(captureId, JSON)
                .paypalRequestId("refund-" + tipId)
                .prefer(RETURN_REPRESENTATION)
                .body(new RefundRequest.Builder().build())
                .build();
        try {
            return fromRefund(
                    client.getPaymentsController().refundCapturedPayment(input).getResult());
        } catch (ErrorException e) {
            String issue = firstIssue(e).orElse(null);
            if (e.getResponseCode() == 422 && "CAPTURE_FULLY_REFUNDED".equals(issue)) {
                return new RefundResult.AlreadyRefunded();
            }
            log.warn(
                    "PayPal refund failed for capture {}: status={} name={} issue={} debugId={}",
                    captureId,
                    e.getResponseCode(),
                    e.getName(),
                    issue,
                    e.getDebugId());
            return new RefundResult.Failed("http " + e.getResponseCode() + (issue == null ? "" : " " + issue));
        } catch (ApiException e) {
            log.warn("PayPal refund failed for capture {}: status={}", captureId, e.getResponseCode());
            return new RefundResult.Failed("http " + e.getResponseCode());
        } catch (IOException | AuthValidationException e) {
            log.warn(
                    "PayPal refund failed for capture {}: {}",
                    captureId,
                    e.getClass().getSimpleName());
            return new RefundResult.Failed(e.getClass().getSimpleName());
        }
    }

    private static RefundResult fromRefund(Refund refund) {
        if (refund == null || refund.getStatus() == null) {
            return new RefundResult.Failed("no refund status in response");
        }
        return switch (refund.getStatus()) {
            case COMPLETED -> new RefundResult.Completed(refund.getId());
            case PENDING -> new RefundResult.Pending(refund.getId());
            default ->
                new RefundResult.Failed("refund status " + refund.getStatus().value());
        };
    }

    private static Optional<String> firstIssue(ErrorException e) {
        return Optional.ofNullable(e.getDetails())
                .flatMap(details -> details.stream().findFirst())
                .map(ErrorDetails::getIssue);
    }
}
