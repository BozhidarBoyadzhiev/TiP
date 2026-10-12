package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;

/**
 * The sample tip seeder exists only under the {@code dev} profile. Render runs without any profile, and a
 * {@code prod} profile, should one be set, does not bring it either. The seeder's dependencies are left out
 * on purpose: the context could not even create it, so a bean showing up here would fail the run.
 */
class DevTipSeederProfileTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(DevTipSeeder.class);

    @Test
    void productionRunsWithoutTheSeeder() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(context).hasNotFailed().doesNotHaveBean(DevTipSeeder.class);
        });
    }

    @Test
    void aProdProfileRunsWithoutTheSeeder() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DevTipSeeder.class));
    }

    @Test
    void onlyTheDevProfileEnablesTheSeeder() {
        assertThat(DevTipSeeder.class.getAnnotation(Profile.class).value()).containsExactly("dev");
    }
}
