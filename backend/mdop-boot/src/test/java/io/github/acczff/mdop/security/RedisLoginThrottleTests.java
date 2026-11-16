package io.github.acczff.mdop.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisLoginThrottleTests {

    private static final String KEY = "testadmin|127.0.0.1";

    @Test
    @SuppressWarnings("unchecked")
    void delegatesFailureBlockedSuccessToRedisAndCountsLockouts() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var counter = meters.counter("mdop.auth.login.lockouts");
        RedisLoginThrottle throttle = new RedisLoginThrottle(redis, 5, 15, 10, counter);

        when(redis.execute(any(RedisScript.class), anyList(), eq("900"), eq("5"), eq("600")))
                .thenReturn(0L, 1L);
        when(redis.hasKey("mdop:auth:login:lock:" + KEY)).thenReturn(true);

        throttle.failure(KEY);
        assertThat(counter.count()).isZero();

        throttle.failure(KEY);
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(throttle.blocked(KEY)).isTrue();

        throttle.success(KEY);
        verify(redis).delete(List.of("mdop:auth:login:fail:" + KEY, "mdop:auth:login:lock:" + KEY));
    }
}
