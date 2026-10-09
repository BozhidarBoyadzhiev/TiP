package dev.tip.tipping;

enum TipStatus {
    CREATED,
    PENDING,
    CAPTURED,
    FAILED,
    REFUNDED,
    ABANDONED;

    boolean capturable() {
        return this == CREATED || this == PENDING;
    }
}
