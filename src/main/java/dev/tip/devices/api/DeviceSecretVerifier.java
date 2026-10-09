package dev.tip.devices.api;

import java.util.Optional;
import java.util.UUID;

/** Authenticates a device by its secret, for the device channel handshake. */
public interface DeviceSecretVerifier {

    /**
     * Returns the device when {@code presentedSecret} (base64url, as sent by the device) matches its
     * stored hash. An unknown device, a wrong secret and a malformed secret all return empty along the
     * same code path, so the response time does not tell them apart.
     */
    Optional<DeviceSummary> verify(UUID deviceId, String presentedSecret);
}
