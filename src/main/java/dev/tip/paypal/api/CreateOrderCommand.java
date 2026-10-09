package dev.tip.paypal.api;

import java.util.Objects;
import java.util.UUID;

/** A PayPal order for exactly one tip. The amount is in minor units of {@code currency}. */
public record CreateOrderCommand(UUID tipId, long amountMinor, String currency, String description) {

    public CreateOrderCommand {
        Objects.requireNonNull(tipId, "tipId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(description, "description");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
    }
}
