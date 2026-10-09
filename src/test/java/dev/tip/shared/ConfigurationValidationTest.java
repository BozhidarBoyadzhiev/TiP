package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import dev.tip.TestData;
import dev.tip.TipApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class ConfigurationValidationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void failsToStartWhenPayPalClientIdIsMissing() {
        assertThatThrownBy(() -> app().run("--PAYPAL_CLIENT_ID="))
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    void failsToStartWithoutAnthropicKeyWhileAiIsEnabled() {
        assertThatThrownBy(() -> app().run("--TIP_AI_ENABLED=true", "--ANTHROPIC_API_KEY="))
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void startsWithoutAnthropicKeyWhileAiIsDisabled() {
        try (ConfigurableApplicationContext context = app().run(
                        "--TIP_AI_ENABLED=false",
                        "--ANTHROPIC_API_KEY=",
                        "--spring.main.web-application-type=servlet",
                        "--server.port=0")) {
            assertThat(context.getBeansOfType(AnthropicClient.class)).isEmpty();
        }
    }

    @Test
    void failsToStartWhenTheDemoOwnerPasswordIsShorterThan12Characters() {
        String shortPassword = "eleven-char";

        assertThatThrownBy(() -> app().run("--TIP_DEMO_OWNER_PASSWORD=" + shortPassword))
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("TIP_DEMO_OWNER_PASSWORD must be 12 to 72 characters")
                .message()
                .doesNotContain(shortPassword);
    }

    @Test
    void failsToStartWhenTheDemoDeviceSecretIsNot32Bytes() {
        String sixteenBytes = "AAAAAAAAAAAAAAAAAAAAAA";

        assertThatThrownBy(() -> app().run("--TIP_DEMO_DEVICE_SECRET=" + sixteenBytes))
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("TIP_DEMO_DEVICE_SECRET must be base64url of exactly 32 bytes")
                .message()
                .doesNotContain(sixteenBytes);
    }

    private static SpringApplicationBuilder app() {
        return new SpringApplicationBuilder(TipApplication.class)
                .profiles("test")
                .properties(
                        "spring.main.web-application-type=none",
                        "TIP_DEMO_DEVICE_SECRET=" + TestData.randomSecret(),
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword());
    }
}
