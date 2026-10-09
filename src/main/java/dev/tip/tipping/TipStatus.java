package dev.tip.tipping;

enum TipStatus {
    CREATED,
    PENDING,
    CAPTURED,
    FAILED,
    REFUNDED,
    /** Captured, then taken back by a chargeback. */
    REVERSED,
    ABANDONED;

    boolean capturable() {
        return this == CREATED || this == PENDING;
    }

    /** The payment can never complete. A tipper has to start a new tip. */
    boolean closed() {
        return this == FAILED || this == ABANDONED;
    }
}
