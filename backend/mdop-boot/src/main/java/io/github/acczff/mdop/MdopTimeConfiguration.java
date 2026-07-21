package io.github.acczff.mdop;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MdopTimeConfiguration {

    @Bean
    Clock mdopClock() {
        return Clock.systemUTC();
    }
}
