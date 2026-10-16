package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The app as every environment but a developer's laptop runs it: without the {@code dev} profile. */
class DevTipSeederAbsentTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    @Test
    void seederDoesNotExistWithoutTheDevProfile() {
        assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("dev");
        assertThat(context.getBeanProvider(DevTipSeeder.class).getIfAvailable()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM tip t JOIN device d ON d.id = t.device_id"
                                + " WHERE d.slug = 'demo-panda' AND t.demo")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void onlyTheDevProfileEnablesTheSeeder() {
        assertThat(DevTipSeeder.class.getAnnotation(Profile.class).value()).containsExactly("dev");
    }
}
