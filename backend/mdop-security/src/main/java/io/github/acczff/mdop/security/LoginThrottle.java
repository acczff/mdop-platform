package io.github.acczff.mdop.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流。
 *
 * <p>按“用户名 + 来源地址”计数：窗口内连续失败达到阈值后锁定一段时间，成功登录立即清零。
 * 目的是把在线撞库的成本从“无限次尝试”提高到“每窗口 5 次”，并避免单账号被他人恶意锁死
 * （键包含来源地址，换 IP 不影响同一用户从其它地址登录）。
 *
 * <p>状态保存在进程内存：单实例足够；多实例部署时每个实例独立计数，届时需换 Redis 实现，
 * 这一点在 {@code docs/40-operations} 的扩展说明中记录。
 */
@Component
public class LoginThrottle {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Duration lock;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public LoginThrottle(
            @Value("${mdop.auth.lockout.max-attempts:5}") int maxAttempts,
            @Value("${mdop.auth.lockout.window-minutes:15}") long windowMinutes,
            @Value("${mdop.auth.lockout.lock-minutes:15}") long lockMinutes,
            Clock clock) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
        this.lock = Duration.ofMinutes(Math.max(1, lockMinutes));
        this.clock = clock;
    }

    /** 请求键：用户名（缺省为空串）+ 来源地址。 */
    public static String key(HttpServletRequest request) {
        String username = request.getParameter("username");
        return (username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT))
                + "|"
                + (request.getRemoteAddr() == null ? "" : request.getRemoteAddr());
    }

    /** 当前是否处于锁定期。 */
    public boolean blocked(String key) {
        State state = states.get(key);
        return state != null
                && state.lockedUntil() != null
                && clock.instant().isBefore(state.lockedUntil());
    }

    /** 记录一次失败；达到阈值后进入锁定期。 */
    public void failure(String key) {
        Instant now = clock.instant();
        if (states.size() > MAX_TRACKED_KEYS) purge(now);
        states.compute(
                key,
                (ignored, previous) -> {
                    boolean expired =
                            previous == null
                                    || previous.firstFailureAt().plus(window).isBefore(now);
                    int failures = expired ? 1 : previous.failures() + 1;
                    Instant firstFailureAt = expired ? now : previous.firstFailureAt();
                    Instant lockedUntil =
                            failures >= maxAttempts
                                    ? now.plus(lock)
                                    : (previous == null ? null : previous.lockedUntil());
                    return new State(failures, firstFailureAt, lockedUntil);
                });
    }

    /** 登录成功后清零。 */
    public void success(String key) {
        states.remove(key);
    }

    private void purge(Instant now) {
        Instant threshold = now.minus(window).minus(lock);
        states.entrySet().removeIf(entry -> entry.getValue().firstFailureAt().isBefore(threshold));
    }

    private record State(int failures, Instant firstFailureAt, Instant lockedUntil) {}
}
