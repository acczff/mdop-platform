package io.github.acczff.mdop.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    @GetMapping("/api/auth/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/api/auth/me")
    public SessionResponse me(Authentication authentication) {
        return new SessionResponse(
                authentication.getName(),
                authentication.getAuthorities().stream().map(Object::toString).toList());
    }

    public record CsrfResponse(String headerName, String token) {}

    public record SessionResponse(String username, List<String> authorities) {}
}
