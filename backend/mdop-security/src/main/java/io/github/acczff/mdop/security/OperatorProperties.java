package io.github.acczff.mdop.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mdop.auth")
public record OperatorProperties(List<Operator> operators) {
    public record Operator(String username, String password, List<String> authorities) {}
}
