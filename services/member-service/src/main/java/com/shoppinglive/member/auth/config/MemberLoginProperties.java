package com.shoppinglive.member.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("member.login")
public record MemberLoginProperties(@DefaultValue("5") int maxFailedAttempts,
                                    @DefaultValue("PT15M") Duration lockDuration) {
    public MemberLoginProperties {
        if (maxFailedAttempts < 1 || lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("Login failure limit and lock duration must be positive");
        }
    }
}
