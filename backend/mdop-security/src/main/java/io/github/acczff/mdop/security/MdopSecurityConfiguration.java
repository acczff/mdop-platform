package io.github.acczff.mdop.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.FindByIndexNameSessionRepository;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(OperatorProperties.class)
public class MdopSecurityConfiguration {

    @Bean
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnProperty(
            name = "mdop.auth.lockout.store",
            havingValue = "redis",
            matchIfMissing = true)
    LoginThrottle redisLoginThrottle(
            StringRedisTemplate redis,
            @Value("${mdop.auth.lockout.max-attempts:5}") int maxAttempts,
            @Value("${mdop.auth.lockout.window-minutes:15}") long windowMinutes,
            @Value("${mdop.auth.lockout.lock-minutes:15}") long lockMinutes,
            ObjectProvider<MeterRegistry> meters) {
        return new RedisLoginThrottle(
                redis, maxAttempts, windowMinutes, lockMinutes, lockoutCounter(meters));
    }

    @Bean
    @ConditionalOnMissingBean(LoginThrottle.class)
    LoginThrottle inMemoryLoginThrottle(
            @Value("${mdop.auth.lockout.max-attempts:5}") int maxAttempts,
            @Value("${mdop.auth.lockout.window-minutes:15}") long windowMinutes,
            @Value("${mdop.auth.lockout.lock-minutes:15}") long lockMinutes,
            Clock clock,
            ObjectProvider<MeterRegistry> meters) {
        return new InMemoryLoginThrottle(
                maxAttempts, windowMinutes, lockMinutes, clock, lockoutCounter(meters));
    }

    private static Counter lockoutCounter(ObjectProvider<MeterRegistry> meters) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry == null) return null;
        return Counter.builder("mdop.auth.login.lockouts")
                .description("Number of login lockout events triggered")
                .register(registry);
    }

    @Bean
    SessionInvalidator sessionInvalidator(
            ObjectProvider<FindByIndexNameSessionRepository<?>> sessions) {
        FindByIndexNameSessionRepository<?> repository = sessions.getIfAvailable();
        if (repository == null) {
            return username -> {};
        }
        return username -> {
            if (username == null || username.isBlank()) return;
            repository.findByPrincipalName(username).keySet().forEach(repository::deleteById);
        };
    }

    @Bean
    SecurityFilterChain mdopSecurityFilterChain(
            HttpSecurity http, AccountDirectory accounts, LoginThrottle throttle) throws Exception {
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
                                                (request, response, authentication) -> {
                                                    throttle.success(LoginThrottle.key(request));
                                                    response.setStatus(204);
                                                })
                                        .failureHandler(
                                                (request, response, exception) -> {
                                                    throttle.failure(LoginThrottle.key(request));
                                                    response.sendError(401);
                                                }))
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .invalidateHttpSession(true)
                                        .deleteCookies("JSESSIONID", "SESSION")
                                        .logoutSuccessHandler(
                                                (request, response, authentication) ->
                                                        response.setStatus(204)));

        http.addFilterBefore(
                new LoginThrottleFilter(throttle),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                        .class);
        http.addFilterBefore(
                new AccountSessionFilter(accounts),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
}
