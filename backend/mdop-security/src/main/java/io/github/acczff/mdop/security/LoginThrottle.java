package io.github.acczff.mdop.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/**
 * 登录失败限流抽象。
 *
 * <p>按“用户名 + 来源地址”计数：窗口内连续失败达到阈值后锁定一段时间，成功登录立即清零。
 * 目的是把在线撞库的成本从“无限次尝试”提高到“每窗口 5 次”，并避免单账号被他人恶意锁死
 * （键包含来源地址，换 IP 不影响同一用户从其它地址登录）。
 */
public interface LoginThrottle {

    /** 请求键：用户名（缺省为空串）+ 来源地址。 */
    static String key(HttpServletRequest request) {
        String username = request.getParameter("username");
        return (username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
                + "|"
                + (request.getRemoteAddr() == null ? "" : request.getRemoteAddr());
    }

    /** 当前是否处于锁定期。 */
    boolean blocked(String key);

    /** 记录一次失败；达到阈值后进入锁定期。 */
    void failure(String key);

    /** 登录成功后清零。 */
    void success(String key);
}
