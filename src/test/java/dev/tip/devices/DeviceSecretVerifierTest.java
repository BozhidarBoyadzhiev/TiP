package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tip.devices.api.DeviceSummary;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceSecretVerifierTest {

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DefaultDeviceSecretVerifier verifier = new DefaultDeviceSecretVerifier(devices);

    private final UUID deviceId = UUID.randomUUID();
    private final byte[] secret = new byte[32];
    private String presented;

    @BeforeEach
    void storedDevice() {
        new SecureRandom().nextBytes(secret);
        presented = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Device device = new Device(
                deviceId, "busker-panda", "Busker Panda", UUID.randomUUID(), DeviceSecrets.hash(secret), Instant.now());
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));
    }

    @Test
    void rightSecretReturnsTheDevice() {
        assertThat(verifier.verify(deviceId, presented))
                .contains(new DeviceSummary(deviceId, "busker-panda", "Busker Panda"));
    }

    @Test
    void secretWithPaddingIsAcceptedToo() {
        assertThat(verifier.verify(deviceId, Base64.getUrlEncoder().encodeToString(secret)))
                .isPresent();
    }

    @Test
    void wrongSecretFails() {
        byte[] other = secret.clone();
        other[0] ^= 1;

        assertThat(verifier.verify(
                        deviceId, Base64.getUrlEncoder().withoutPadding().encodeToString(other)))
                .isEmpty();
    }

    @Test
    void unknownDeviceFailsEvenWithAValidSecret() {
        assertThat(verifier.verify(UUID.randomUUID(), presented)).isEmpty();
    }

    @Test
    void malformedOrMissingSecretFails() {
        assertThat(verifier.verify(deviceId, "not base64 !")).isEmpty();
        assertThat(verifier.verify(deviceId, presented.substring(0, 20))).isEmpty();
        assertThat(verifier.verify(deviceId, "")).isEmpty();
        assertThat(verifier.verify(deviceId, null)).isEmpty();
        assertThat(verifier.verify(null, presented)).isEmpty();
    }
}
