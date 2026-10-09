package dev.tip.paypal;

import dev.tip.paypal.api.CaptureResult;
import dev.tip.paypal.api.CreateOrderCommand;
import dev.tip.paypal.api.CreateOrderResult;
import dev.tip.paypal.api.PayPalGateway;
import dev.tip.paypal.api.WebhookTransmission;
import dev.tip.paypal.api.WebhookVerification;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class DefaultPayPalGateway implements PayPalGateway {

    private final PayPalOrders orders;
    private final WebhookSignatureVerifier webhookVerifier;

    DefaultPayPalGateway(PayPalOrders orders, WebhookSignatureVerifier webhookVerifier) {
        this.orders = orders;
        this.webhookVerifier = webhookVerifier;
    }

    @Override
    public CreateOrderResult createOrder(CreateOrderCommand command) {
        return orders.create(command);
    }

    @Override
    public CaptureResult captureOrder(UUID tipId, String orderId) {
        return orders.capture(tipId, orderId);
    }

    @Override
    public CaptureResult lookupOrder(String orderId) {
        return orders.lookup(orderId);
    }

    @Override
    public WebhookVerification verifyWebhook(WebhookTransmission transmission, String rawBody) {
        return webhookVerifier.verify(transmission, rawBody);
    }
}
