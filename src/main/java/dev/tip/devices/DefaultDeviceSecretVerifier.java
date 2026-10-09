package dev.tip.devices;

import dev.tip.devices.api.DeviceSecretVerifier;
import dev.tip.devices.api.DeviceSummary;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DefaultDeviceSecretVerifier implements DeviceSecretVerifier {

    /** Compared against when the device or the secret is unusable, so every call hashes and compares once. */
    private static final byte[] NO_MATCH_SECRET = randomBytes();

    private static final byte[] NO_MATCH_HASH = DeviceSecrets.hash(randomBytes());

    private final DeviceRepository devices;

    DefaultDeviceSecretVerifier(DeviceRepository devices) {
        this.devices = devices;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceSummary> verify(UUID deviceId, String presentedSecret) {
        Optional<Device> device = deviceId == null ? Optional.empty() : devices.findById(deviceId);
        Optional<byte[]> secret = DeviceSecrets.decode(presentedSecret);
        byte[] presentedHash = DeviceSecrets.hash(secret.orElse(NO_MATCH_SECRET));
        byte[] storedHash = device.map(Device::secretHash).orElse(NO_MATCH_HASH);
        boolean matches = MessageDigest.isEqual(presentedHash, storedHash);
        return matches && device.isPresent() && secret.isPresent()
                ? device.map(DeviceService::summary)
                : Optional.empty();
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[DeviceSecrets.SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
