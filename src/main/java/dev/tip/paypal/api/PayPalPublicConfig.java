package dev.tip.paypal.api;

/** PayPal settings that are safe to send to a browser. */
public record PayPalPublicConfig(String clientId) {}
