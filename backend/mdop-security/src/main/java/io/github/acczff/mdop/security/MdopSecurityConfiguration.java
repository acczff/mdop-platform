package io.github.acczff.mdop.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(OperatorProperties.class)
public class MdopSecurityConfiguration {

    @Bean
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain mdopSecurityFilterChain(HttpSecurity http, AccountDirectory accounts)
            throws Exception {
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

        http.addFilterBefore(
                new AccountSessionFilter(accounts),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
}
