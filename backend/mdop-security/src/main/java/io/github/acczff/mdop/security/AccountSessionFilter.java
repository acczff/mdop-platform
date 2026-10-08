package io.github.acczff.mdop.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class AccountSessionFilter extends OncePerRequestFilter {
    private final AccountDirectory accounts;

    AccountSessionFilter(AccountDirectory accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.getPrincipal() instanceof AccountPrincipal user
                && !accounts.sessionValid(user)) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            response.sendError(401);
            return;
        }
        chain.doFilter(request, response);
    }
}
