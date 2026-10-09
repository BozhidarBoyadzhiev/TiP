package dev.tip.paypal;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.exceptions.ErrorException;
import com.paypal.sdk.models.AmountWithBreakdown;
import com.paypal.sdk.models.CaptureOrderInput;
import com.paypal.sdk.models.CheckoutPaymentIntent;
import com.paypal.sdk.models.CreateOrderInput;
import com.paypal.sdk.models.ErrorDetails;
import com.paypal.sdk.models.GetOrderInput;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrdersCapture;
import com.paypal.sdk.models.PaymentCollection;
import com.paypal.sdk.models.PaymentSource;
import com.paypal.sdk.models.PaypalExperienceUserAction;
import com.paypal.sdk.models.PaypalWallet;
import com.paypal.sdk.models.PaypalWalletContextShippingPreference;
import com.paypal.sdk.models.PaypalWalletExperienceContext;
import com.paypal.sdk.models.PurchaseUnit;
import com.paypal.sdk.models.PurchaseUnitRequest;
import dev.tip.paypal.api.CaptureResult;
import dev.tip.paypal.api.CreateOrderCommand;
import dev.tip.paypal.api.CreateOrderResult;
import io.apimatic.core.exceptions.AuthValidationException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Orders v2 through the PayPal Server SDK. Translates every SDK failure into a typed result. */
@Component
class PayPalOrders {

    private static final Logger log = LoggerFactory.getLogger(PayPalOrders.class);
    private static final String JSON = "application/json";
    private static final String RETURN_REPRESENTATION = "return=representation";

    private final PaypalServerSdkClient client;

    PayPalOrders(PaypalServerSdkClient client) {
        this.client = client;
    }

    CreateOrderResult create(CreateOrderCommand command) {
        String tipId = command.tipId().toString();
        AmountWithBreakdown amount = new AmountWithBreakdown.Builder(
                        command.currency(), formatAmount(command.amountMinor(), command.currency()))
                .build();
        PurchaseUnitRequest purchaseUnit = new PurchaseUnitRequest.Builder(amount)
                .customId(tipId)
                .description(command.description())
                .build();
        PaypalWalletExperienceContext experience = new PaypalWalletExperienceContext.Builder()
                .shippingPreference(PaypalWalletContextShippingPreference.NO_SHIPPING)
                .userAction(PaypalExperienceUserAction.PAY_NOW)
                .build();
        OrderRequest order = new OrderRequest.Builder(CheckoutPaymentIntent.CAPTURE, List.of(purchaseUnit))
                .paymentSource(new PaymentSource.Builder()
                        .paypal(new PaypalWallet.Builder()
                                .experienceContext(experience)
                                .build())
                        .build())
                .build();
        CreateOrderInput input = new CreateOrderInput.Builder(JSON, order)
                .paypalRequestId("create-" + tipId)
                .build();
        try {
            String orderId =
                    client.getOrdersController().createOrder(input).getResult().getId();
            if (orderId == null || orderId.isBlank()) {
                return new CreateOrderResult.Failed("no order id in response");
            }
            return new CreateOrderResult.Created(orderId);
        } catch (ErrorException e) {
            log.warn(
                    "PayPal create order failed for tip {}: status={} name={} issue={} debugId={}",
                    tipId,
                    e.getResponseCode(),
                    e.getName(),
                    firstIssue(e).orElse(null),
                    e.getDebugId());
            return new CreateOrderResult.Failed("http " + e.getResponseCode());
        } catch (ApiException e) {
            log.warn("PayPal create order failed for tip {}: status={}", tipId, e.getResponseCode());
            return new CreateOrderResult.Failed("http " + e.getResponseCode());
        } catch (IOException | AuthValidationException e) {
            log.warn(
                    "PayPal create order failed for tip {}: {}",
                    tipId,
                    e.getClass().getSimpleName());
            return new CreateOrderResult.Failed(e.getClass().getSimpleName());
        }
    }

    CaptureResult capture(UUID tipId, String orderId) {
        CaptureOrderInput input = new CaptureOrderInput.Builder(orderId, JSON)
                .paypalRequestId("capture-" + tipId)
                .prefer(RETURN_REPRESENTATION)
                .build();
        try {
            return fromOrder(client.getOrdersController().captureOrder(input).getResult());
        } catch (ErrorException e) {
            return fromError("capture", orderId, e);
        } catch (ApiException e) {
            log.warn("PayPal capture failed for order {}: status={}", orderId, e.getResponseCode());
            return new CaptureResult.Failed("http " + e.getResponseCode());
        } catch (IOException | AuthValidationException e) {
            log.warn(
                    "PayPal capture failed for order {}: {}",
                    orderId,
                    e.getClass().getSimpleName());
            return new CaptureResult.Failed(e.getClass().getSimpleName());
        }
    }

    CaptureResult lookup(String orderId) {
        try {
            Order order = client.getOrdersController()
                    .getOrder(new GetOrderInput.Builder(orderId).build())
                    .getResult();
            return fromOrder(order);
        } catch (ErrorException e) {
            return fromError("get order", orderId, e);
        } catch (ApiException e) {
            log.warn("PayPal get order failed for order {}: status={}", orderId, e.getResponseCode());
            return new CaptureResult.Failed("http " + e.getResponseCode());
        } catch (IOException | AuthValidationException e) {
            log.warn(
                    "PayPal get order failed for order {}: {}",
                    orderId,
                    e.getClass().getSimpleName());
            return new CaptureResult.Failed(e.getClass().getSimpleName());
        }
    }

    /** PayPal amounts are decimal strings in major units, for example 250 GBP minor units is "2.50". */
    static String formatAmount(long amountMinor, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return BigDecimal.valueOf(amountMinor, digits).toPlainString();
    }

    private static CaptureResult fromOrder(Order order) {
        Optional<OrdersCapture> capture = Optional.ofNullable(order)
                .map(Order::getPurchaseUnits)
                .flatMap(units -> units.stream().findFirst())
                .map(PurchaseUnit::getPayments)
                .map(PaymentCollection::getCaptures)
                .flatMap(captures -> captures.stream().findFirst());
        if (capture.isEmpty() || capture.get().getStatus() == null) {
            return new CaptureResult.Failed("no capture in order");
        }
        String captureId = capture.get().getId();
        return switch (capture.get().getStatus()) {
            case COMPLETED -> new CaptureResult.Completed(captureId);
            case PENDING -> new CaptureResult.Pending(captureId);
            case DECLINED, FAILED -> new CaptureResult.Declined();
            default ->
                new CaptureResult.Failed(
                        "capture status " + capture.get().getStatus().value());
        };
    }

    private static CaptureResult fromError(String operation, String orderId, ErrorException e) {
        String issue = firstIssue(e).orElse(null);
        if (e.getResponseCode() == 422 && "INSTRUMENT_DECLINED".equals(issue)) {
            return new CaptureResult.Declined();
        }
        if (e.getResponseCode() == 422 && "ORDER_ALREADY_CAPTURED".equals(issue)) {
            return new CaptureResult.AlreadyCaptured();
        }
        if (e.getResponseCode() == 404) {
            return new CaptureResult.OrderNotFound();
        }
        log.warn(
                "PayPal {} failed for order {}: status={} name={} issue={} debugId={}",
                operation,
                orderId,
                e.getResponseCode(),
                e.getName(),
                issue,
                e.getDebugId());
        return new CaptureResult.Failed("http " + e.getResponseCode() + (issue == null ? "" : " " + issue));
    }

    private static Optional<String> firstIssue(ErrorException e) {
        return Optional.ofNullable(e.getDetails())
                .flatMap(details -> details.stream().findFirst())
                .map(ErrorDetails::getIssue);
    }
}
