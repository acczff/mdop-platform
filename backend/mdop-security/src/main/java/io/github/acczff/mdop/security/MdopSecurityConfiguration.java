package io.github.acczff.mdop.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(OperatorProperties.class)
public class MdopSecurityConfiguration {

    @Bean
    UserDetailsService localOperator(
            @Value("${mdop.auth.username}") String username,
            @Value("${mdop.auth.password}") String password,
            OperatorProperties operators) {
        if (!username.matches("[A-Za-z][A-Za-z0-9._-]{0,63}") || password.length() < 12) {
            throw new IllegalArgumentException(
                    "请配置合法的 MDOP_ADMIN_USERNAME 和至少12位的 MDOP_ADMIN_PASSWORD");
        }
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var manager =
                new InMemoryUserDetailsManager(
                        User.withUsername(username)
                                .password(encoder.encode(password))
                                .roles("ADMIN", "RECEIVER")
                                .build());
        if (operators.operators() != null)
            for (var operator : operators.operators()) {
                if (operator.username() == null
                        || !operator.username().matches("[A-Za-z][A-Za-z0-9._-]{0,63}")
                        || operator.password() == null
                        || operator.password().length() < 12
                        || operator.authorities() == null
                        || operator.authorities().isEmpty()
                        || manager.userExists(operator.username()))
                    throw new IllegalArgumentException("操作账号配置不合法或重名");
                var authorities = operator.authorities();
                for (String authority : authorities)
                    if (!authority.matches("wms:warehouse:[1-9][0-9]*")
                            && !java.util.Set.of(
                                            "warehouse:read",
                                            "wms:arrival:read",
                                            "wms:receipt:create",
                                            "wms:receipt:submit",
                                            "wms:correction:read",
                                            "wms:correction:create",
                                            "wms:correction:approve")
                                    .contains(authority))
                        throw new IllegalArgumentException("操作账号包含不受支持的权限");
                if (authorities.contains("wms:correction:approve")
                        && (authorities.contains("wms:correction:create")
                                || authorities.contains("wms:receipt:submit")))
                    throw new IllegalArgumentException("审批账号不能同时配置申请或收货提交权限");
                manager.createUser(
                        User.withUsername(operator.username())
                                .password(encoder.encode(operator.password()))
                                .authorities(authorities.toArray(String[]::new))
                                .build());
            }
        return manager;
    }

    @Bean
    SecurityFilterChain mdopSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(
                                                "/actuator/health",
                                                "/api/auth/csrf",
                                                "/api/auth/login")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        response.sendError(401))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        response.sendError(403)))
                .formLogin(
                        login ->
                                login.loginProcessingUrl("/api/auth/login")
                                        .successHandler(
                                                (request, response, authentication) ->
                                                        response.setStatus(204))
                                        .failureHandler(
                                                (request, response, exception) ->
                                                        response.sendError(401)))
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .invalidateHttpSession(true)
                                        .deleteCookies("JSESSIONID")
                                        .logoutSuccessHandler(
                                                (request, response, authentication) ->
                                                        response.setStatus(204)));

        return http.build();
    }
}
