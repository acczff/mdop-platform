package io.github.acczff.mdop.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginThrottleFilterTests {

    private static final String USER = "probe";
    private static final String ADDRESS = "10.0.0.9";

    private static LoginThrottle lockedThrottle() {
        LoginThrottle throttle = new LoginThrottle(1, 15, 15, Clock.systemUTC());
        throttle.failure(USER + "|" + ADDRESS);
        return throttle;
    }

    private static MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(ADDRESS);
        request.addParameter("username", USER);
        return request;
    }

    @Test
    void rejectsLockedLoginWithTooManyRequests() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LoginThrottleFilter(lockedThrottle()).doFilter(loginRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesWhenNotLocked() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LoginThrottleFilter(new LoginThrottle(5, 15, 15, Clock.systemUTC()))
                .doFilter(loginRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void ignoresNonLoginRequests() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        request.setRemoteAddr(ADDRESS);
        request.addParameter("username", USER);

        new LoginThrottleFilter(lockedThrottle()).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void recognisesLoginUnderContextPath() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/api/auth/login");
        request.setContextPath("/app");
        request.setRemoteAddr(ADDRESS);
        request.addParameter("username", USER);

        new LoginThrottleFilter(lockedThrottle()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chain.getRequest()).isNull();
    }
}
