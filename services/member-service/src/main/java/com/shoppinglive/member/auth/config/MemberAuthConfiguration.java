package com.shoppinglive.member.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MemberTokenProperties.class, MemberLoginProperties.class})
public class MemberAuthConfiguration {
    @Bean Clock memberClock() { return Clock.systemUTC(); }
}
