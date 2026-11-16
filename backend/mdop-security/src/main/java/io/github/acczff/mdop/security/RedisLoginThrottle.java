package io.github.acczff.mdop.security;

import io.micrometer.core.instrument.Counter;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 基于 Redis 的分布式登录失败限流实现。
 *
 * <p>使用 Lua 脚本原子递增窗口内失败次数并在达到阈值时写入锁定键，保持与内存实现相同的键与时间窗口语义。
 */
public class RedisLoginThrottle implements LoginThrottle {

    private static final String FAIL_PREFIX = "mdop:auth:login:fail:";
    private static final String LOCK_PREFIX = "mdop:auth:login:lock:";

    private static final DefaultRedisScript<Long> FAILURE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                      redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
                    end
                    if count >= tonumber(ARGV[2]) then
                      local newly = redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[3]), 'NX')
                      if newly then
                        return 1
                      end
                    end
                    return 0
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final long windowSeconds;
    private final long lockSeconds;
    private final Counter lockoutCounter;

    public RedisLoginThrottle(
            StringRedisTemplate redis,
            int maxAttempts,
            long windowMinutes,
            long lockMinutes,
            Counter lockoutCounter) {
        this.redis = redis;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.windowSeconds = Math.max(1, windowMinutes) * 60L;
        this.lockSeconds = Math.max(1, lockMinutes) * 60L;
        this.lockoutCounter = lockoutCounter;
    }

    @Override
    public boolean blocked(String key) {
        return Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + key));
    }

    @Override
    public void failure(String key) {
        Long newlyLocked =
                redis.execute(
                        FAILURE_SCRIPT,
                        List.of(FAIL_PREFIX + key, LOCK_PREFIX + key),
                        String.valueOf(windowSeconds),
                        String.valueOf(maxAttempts),
                        String.valueOf(lockSeconds));
        if (Long.valueOf(1L).equals(newlyLocked) && lockoutCounter != null) {
            lockoutCounter.increment();
        }
    }

    @Override
    public void success(String key) {
        redis.delete(List.of(FAIL_PREFIX + key, LOCK_PREFIX + key));
    }
}
