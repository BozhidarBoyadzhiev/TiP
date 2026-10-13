package dev.tip.owners.api;

/**
 * The three access levels. {@code REAL} owners sign in with email and password and may own physical devices. A
 * {@code SANDBOX} owner is made by an access code, expires and owns one virtual panda. The single {@code
 * PUBLIC_DEMO} owner is the shared read only demo dashboard.
 */
public enum OwnerKind {
    REAL,
    SANDBOX,
    PUBLIC_DEMO;

    /** Its devices have no hardware: the device channel turns them away like an unknown device. */
    public boolean virtualOnly() {
        return this != REAL;
    }
}
