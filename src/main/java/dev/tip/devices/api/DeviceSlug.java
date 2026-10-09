package dev.tip.devices.api;

/** Format of the device slug written on the NFC tag, see {@code docs/DEVICE_PROTOCOL.md}. */
public final class DeviceSlug {

    public static final String PATTERN = "[a-z0-9-]{3,40}";

    private DeviceSlug() {}
}
