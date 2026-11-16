package io.github.acczff.mdop.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginThrottleTests {

    private static final String KEY = "testadmin|127.0.0.1";

    @Test
    void blocksAfterConfiguredFailuresAndClearsOnSuccess() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(5, 15, 15, clock);

        for (int attempt = 0; attempt < 4; attempt++) {
            throttle.failure(KEY);
            assertThat(throttle.blocked(KEY)).isFalse();
        }

        throttle.failure(KEY);
        assertThat(throttle.blocked(KEY)).isTrue();

        throttle.success(KEY);
        assertThat(throttle.blocked(KEY)).isFalse();
    }

    @Test
    void failureWindowExpiresWithoutLock() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(3, 15, 15, clock);

        throttle.failure(KEY);
        throttle.failure(KEY);
        clock.advance(Duration.ofMinutes(16));
        throttle.failure(KEY);

        assertThat(throttle.blocked(KEY)).isFalse();
    }

    @Test
    void lockReleasesAfterConfiguredMinutes() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(2, 15, 10, clock);

        throttle.failure(KEY);
        throttle.failure(KEY);
        assertThat(throttle.blocked(KEY)).isTrue();

        clock.advance(Duration.ofMinutes(9));
        assertThat(throttle.blocked(KEY)).isTrue();

        clock.advance(Duration.ofMinutes(2));
        assertThat(throttle.blocked(KEY)).isFalse();
    }

    @Test
    void differentSourceAddressIsTrackedSeparately() {
        LoginThrottle throttle = new LoginThrottle(1, 15, 15, new MutableClock());

        throttle.failure("testadmin|10.0.0.1");

        assertThat(throttle.blocked("testadmin|10.0.0.1")).isTrue();
        assertThat(throttle.blocked("testadmin|10.0.0.2")).isFalse();
    }

    /** 可推进的时钟，避免测试依赖真实等待。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
