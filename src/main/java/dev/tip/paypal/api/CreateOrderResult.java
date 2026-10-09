package dev.tip.paypal.api;

public sealed interface CreateOrderResult {

    record Created(String orderId) implements CreateOrderResult {}

    /** PayPal rejected the order or could not be reached. {@code reason} is safe to log. */
    record Failed(String reason) implements CreateOrderResult {}
}
