package io.github.acczff.mdop.security;

import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SpringSecurityCurrentActorProvider implements CurrentActorProvider {

    @Override
    public String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("当前操作缺少已认证身份");
        }

        return authentication.getName();
    }
}
