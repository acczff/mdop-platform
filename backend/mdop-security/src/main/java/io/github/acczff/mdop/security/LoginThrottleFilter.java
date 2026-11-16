package io.github.acczff.mdop.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 登录入口的限流前置检查：处于锁定期时直接返回 429，不再校验口令。
 *
 * <p>放在 {@code UsernamePasswordAuthenticationFilter} 之前，避免锁定期间仍然消耗口令哈希的计算。
 * 只拦截 {@code POST /api/auth/login}，其余请求原样放行。
 */
final class LoginThrottleFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/auth/login";

    /**
     * 判断是否为登录请求。
     *
     * <p>同时接受 servletPath 与去掉 context-path 后的 requestURI：前者在容器内可靠，
     * 后者覆盖代理与测试环境；只做精确匹配，不用 endsWith，避免被相似路径绕过。
     */
    private static boolean isLoginRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return false;
        if (LOGIN_PATH.equals(request.getServletPath())) return true;
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null) return false;
        if (context != null && !context.isEmpty() && uri.startsWith(context))
            uri = uri.substring(context.length());
        return LOGIN_PATH.equals(uri);
    }

    private final LoginThrottle throttle;

    LoginThrottleFilter(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isLoginRequest(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (!throttle.blocked(LoginThrottle.key(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write(
                        "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                                + "\"detail\":\"登录失败次数过多，请稍后再试\"}");
    }
}
