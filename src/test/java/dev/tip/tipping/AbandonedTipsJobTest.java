package dev.tip.tipping;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AbandonedTipsJobTest extends IntegrationTest {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    TipRepository tips;

    @Autowired
    TestData data;

    @Test
    void createdTipsOlderThanSixHoursBecomeAbandoned() {
        UUID deviceId = data.device();
        UUID old = data.tip(
                deviceId, "CREATED", null, now.minus(Duration.ofHours(6)).minusSeconds(1));
        UUID recent = data.tip(deviceId, "CREATED", null, now.minus(Duration.ofHours(5)));
        UUID oldCaptured = data.tip(deviceId, "CAPTURED", null, now.minus(Duration.ofDays(2)));
        UUID oldPending = data.tip(deviceId, "PENDING", null, now.minus(Duration.ofDays(2)));

        new AbandonedTipsJob(tips, Clock.fixed(now, ZoneOffset.UTC)).abandonStaleTips();

        assertThat(data.tipStatus(old)).isEqualTo("ABANDONED");
        assertThat(data.tipStatus(recent)).isEqualTo("CREATED");
        assertThat(data.tipStatus(oldCaptured)).isEqualTo("CAPTURED");
        assertThat(data.tipStatus(oldPending)).isEqualTo("PENDING");
    }
}
