package io.github.acczff.mdop.security;

import io.micrometer.core.instrument.Counter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 基于进程内存的登录失败限流实现，适用于未启用 Redis 的单实例或单元测试。 */
public class InMemoryLoginThrottle implements LoginThrottle {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Duration lock;
    private final Clock clock;
    private final Counter lockoutCounter;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public InMemoryLoginThrottle(
            int maxAttempts, long windowMinutes, long lockMinutes, Clock clock) {
        this(maxAttempts, windowMinutes, lockMinutes, clock, null);
    }

    public InMemoryLoginThrottle(
            int maxAttempts,
            long windowMinutes,
            long lockMinutes,
            Clock clock,
            Counter lockoutCounter) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
        this.lock = Duration.ofMinutes(Math.max(1, lockMinutes));
        this.clock = clock;
        this.lockoutCounter = lockoutCounter;
    }

    @Override
    public boolean blocked(String key) {
        State state = states.get(key);
        return state != null
                && state.lockedUntil() != null
                && clock.instant().isBefore(state.lockedUntil());
    }

    @Override
    public void failure(String key) {
        Instant now = clock.instant();
        if (states.size() > MAX_TRACKED_KEYS) purge(now);
        boolean[] newlyLocked = {false};
        states.compute(
                key,
                (ignored, previous) -> {
                    boolean expired =
                            previous == null
                                    || previous.firstFailureAt().plus(window).isBefore(now);
                    int failures = expired ? 1 : previous.failures() + 1;
                    Instant firstFailureAt = expired ? now : previous.firstFailureAt();
                    boolean wasLocked =
                            !expired
                                    && previous != null
                                    && previous.lockedUntil() != null
                                    && now.isBefore(previous.lockedUntil());
                    Instant lockedUntil =
                            failures >= maxAttempts
                                    ? now.plus(lock)
                                    : (previous == null ? null : previous.lockedUntil());
                    if (failures >= maxAttempts && !wasLocked) {
                        newlyLocked[0] = true;
                    }
                    return new State(failures, firstFailureAt, lockedUntil);
                });
        if (newlyLocked[0] && lockoutCounter != null) {
            lockoutCounter.increment();
        }
    }

    @Override
    public void success(String key) {
        states.remove(key);
    }

    private void purge(Instant now) {
        Instant threshold = now.minus(window).minus(lock);
        states.entrySet().removeIf(entry -> entry.getValue().firstFailureAt().isBefore(threshold));
    }

    private record State(int failures, Instant firstFailureAt, Instant lockedUntil) {}
}
