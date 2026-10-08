package io.github.acczff.mdop.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class AccountPrincipal extends User {
    private final long version;

    public AccountPrincipal(
            String username,
            String password,
            boolean enabled,
            long version,
            Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, true, authorities);
        this.version = version;
    }

    public long version() {
        return version;
    }
}
