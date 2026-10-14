package com.shoppinglive.member.auth.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MemberTokenProperties.class, MemberLoginProperties.class})
public class MemberAuthConfiguration {
    @Bean
    Clock memberClock() {
        return Clock.systemUTC();
    }
}
