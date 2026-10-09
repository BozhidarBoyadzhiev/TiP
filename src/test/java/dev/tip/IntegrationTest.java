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
 * PayPal stubbed by one WireMock server shared by every test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final WireMockServer PAYPAL =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PAYPAL.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PAYPAL::stop));
    }

    @DynamicPropertySource
    static void payPalBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("PAYPAL_BASE_URL", PAYPAL::baseUrl);
    }

    @BeforeEach
    void resetPayPal() {
        PAYPAL.resetAll();
        PayPalStubs.token(PAYPAL);
    }
}
