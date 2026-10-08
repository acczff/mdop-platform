package io.github.acczff.mdop.system.identity;

import io.github.acczff.mdop.security.OperatorProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AccountBootstrap implements ApplicationRunner {
    private final AccountService accounts;
    private final String username, password;
    private final OperatorProperties operators;

    public AccountBootstrap(
            AccountService accounts,
            @Value("${mdop.auth.username}") String username,
            @Value("${mdop.auth.password}") String password,
            OperatorProperties operators) {
        this.accounts = accounts;
        this.username = username;
        this.password = password;
        this.operators = operators;
    }

    public void run(ApplicationArguments args) {
        accounts.bootstrap(username, password, operators);
    }
}
