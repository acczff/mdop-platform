package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MdopTimeConfigurationTests {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(MdopTimeConfiguration.class);

    @Test
    void defaultsToShanghaiWithoutDependingOnJvmTimezone() {
        context.run(
                c ->
                        assertThat(c.getBean(Clock.class).getZone())
                                .isEqualTo(ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void allowsExplicitBusinessTimezone() {
        context.withPropertyValues("mdop.time-zone=America/New_York")
                .run(
                        c ->
                                assertThat(c.getBean(Clock.class).getZone())
                                        .isEqualTo(ZoneId.of("America/New_York")));
    }

    @Test
    void rejectsInvalidTimezoneInsteadOfSilentlyUsingUtc() {
        context.withPropertyValues("mdop.time-zone=invalid-zone")
                .run(c -> assertThat(c).hasFailed());
    }
}
