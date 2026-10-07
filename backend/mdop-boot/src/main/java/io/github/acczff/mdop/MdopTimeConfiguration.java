package io.github.acczff.mdop;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MdopTimeConfiguration {

    @Bean
    Clock mdopClock(@Value("${mdop.time-zone:Asia/Shanghai}") String timeZone) {
        // The zone controls business dates; instant() still represents the same UTC instant.
        return Clock.system(ZoneId.of(timeZone));
    }
}
