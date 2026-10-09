package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tip.TipApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
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
        SpringApplicationBuilder app = new SpringApplicationBuilder(TipApplication.class)
                .profiles("test")
                .properties(
                        "spring.main.web-application-type=none",
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword());

        assertThatThrownBy(() -> app.run("--PAYPAL_CLIENT_ID="))
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("clientId");
    }
}
