package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Which port the app listens on, resolved the way Spring Boot resolves it: environment variables ahead
 * of {@code application.properties}, with relaxed binding from {@code SERVER_PORT} to {@code
 * server.port}.
 */
class ServerPortTest {

    @Test
    void listensOn8080WithoutPortVariables() throws IOException {
        assertThat(serverPort(Map.of())).isEqualTo(8080);
    }

    @Test
    void listensOnThePortThePlatformSets() throws IOException {
        assertThat(serverPort(Map.of("PORT", "10000"))).isEqualTo(10000);
    }

    @Test
    void serverPortVariableWinsOverPort() throws IOException {
        assertThat(serverPort(Map.of("SERVER_PORT", "8081"))).isEqualTo(8081);
        assertThat(serverPort(Map.of("PORT", "10000", "SERVER_PORT", "8081"))).isEqualTo(8081);
    }

    private static int serverPort(Map<String, Object> environmentVariables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));
        new PropertiesPropertySourceLoader()
                .load("application.properties", new ClassPathResource("application.properties"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("server.port", Integer.class).get();
    }
}
