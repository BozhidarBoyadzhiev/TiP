package dev.tip;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that run the whole app on a random port against PostgreSQL from Testcontainers, with
 * PayPal and Anthropic stubbed by WireMock servers shared by every test class. Anthropic answers NEUTRAL
 * unless a test stubs something more specific.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestData.class, CapturedEvents.class, ReactedEvents.class})
public abstract class IntegrationTest {

    /** The demo device's secret, generated per test run so no secret is committed. */
    protected static final String DEMO_DEVICE_SECRET = TestData.randomSecret();

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
    }

    @BeforeEach
    void resetStubs() {
        PAYPAL.resetAll();
        PayPalStubs.token(PAYPAL);
        ANTHROPIC.resetAll();
        AnthropicStubs.fallback(ANTHROPIC);
    }
}
