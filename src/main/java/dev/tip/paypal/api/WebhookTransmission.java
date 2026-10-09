package dev.tip.paypal.api;

/** The five {@code PAYPAL-*} transmission headers PayPal sends with every webhook. Any value may be null. */
public record WebhookTransmission(
        String authAlgo, String certUrl, String transmissionId, String transmissionSig, String transmissionTime) {

    public boolean complete() {
        return present(authAlgo)
                && present(certUrl)
                && present(transmissionId)
                && present(transmissionSig)
                && present(transmissionTime);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
