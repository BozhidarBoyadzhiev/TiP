package dev.tip;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.tip.owners.AccessCode;
import java.security.SecureRandom;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that run the whole app on a random port against PostgreSQL from Testcontainers, with
 * PayPal and Anthropic stubbed by WireMock servers shared by every test class. Anthropic answers PLAIN
 * unless a test stubs something more specific.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
    TestcontainersConfiguration.class,
    TestData.class,
    CapturedEvents.class,
    ReactedEvents.class,
    IntegrationTest.ClockConfiguration.class
})
public abstract class IntegrationTest {

    /** The demo device's secret, generated per test run so no secret is committed. */
    protected static final String DEMO_DEVICE_SECRET = TestData.randomSecret();

    /** Access codes, generated per test run like the secret. {@code judges} never expires in a test. */
    protected static final String JUDGE_CODE = AccessCode.display(AccessCode.generate(new SecureRandom()));

    /** Expires at {@link #SHORT_CODE_EXPIRES}; tests pin the clock around it. */
    protected static final String SHORT_CODE = AccessCode.display(AccessCode.generate(new SecureRandom()));

    protected static final Instant SHORT_CODE_EXPIRES = Instant.parse("2027-01-01T00:00:00Z");

    protected static final String EXPIRED_CODE = AccessCode.display(AccessCode.generate(new SecureRandom()));

    protected static final WireMockServer PAYPAL =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    protected static final WireMockServer ANTHROPIC =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PAYPAL.start();
        ANTHROPIC.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PAYPAL.stop();
            ANTHROPIC.stop();
        }));
    }

    @DynamicPropertySource
    static void stubBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("PAYPAL_BASE_URL", PAYPAL::baseUrl);
        registry.add("ANTHROPIC_BASE_URL", ANTHROPIC::baseUrl);
        registry.add("TIP_DEMO_DEVICE_SECRET", () -> DEMO_DEVICE_SECRET);
        registry.add(
                "TIP_ACCESS_CODES",
                () -> "judges:" + AccessCode.hashHex(JUDGE_CODE) + ":2099-01-01T00:00:00Z;"
                        + "short:" + AccessCode.hashHex(SHORT_CODE) + ":" + SHORT_CODE_EXPIRES + ";"
                        + "old:" + AccessCode.hashHex(EXPIRED_CODE) + ":2020-01-01T00:00:00+01:00");
    }

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.release();
    }

    @BeforeEach
    void resetStubs() {
        PAYPAL.resetAll();
        PayPalStubs.token(PAYPAL);
        ANTHROPIC.resetAll();
        AnthropicStubs.fallback(ANTHROPIC);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
