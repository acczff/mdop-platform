package io.github.acczff.mdop.security;

import org.springframework.security.core.userdetails.UserDetailsService;

public interface AccountDirectory extends UserDetailsService {
    boolean sessionValid(AccountPrincipal user);
}
