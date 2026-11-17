package io.github.acczff.mdop.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.context.ActiveProfiles;

/** 多实例部署依赖共享状态：有 Redis 时限流与会话必须真正落在 Redis 上，而不是静默退回进程内实现。 */
@SpringBootTest
@ActiveProfiles("test")
class LoginThrottleWiringTests extends MdopInfrastructureTestBase {
    @Autowired LoginThrottle throttle;
    @Autowired SessionInvalidator invalidator;
    @Autowired FindByIndexNameSessionRepository<?> sessions;

    @Test
    void usesRedisBackedThrottleAndIndexedSessionsWhenRedisIsAvailable() {
        assertThat(throttle).isInstanceOf(RedisLoginThrottle.class);
        assertThat(sessions).isNotNull();
        assertThat(invalidator).isNotNull();
    }
}
