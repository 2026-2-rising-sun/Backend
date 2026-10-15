package com.shoppinglive.member.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("member.login")
public record MemberLoginProperties(
        @DefaultValue("5") int maxFailedAttempts, @DefaultValue("PT15M") Duration lockDuration) {
    public MemberLoginProperties {
        if (maxFailedAttempts < 1
                || lockDuration == null
                || lockDuration.isZero()
                || lockDuration.isNegative()) {
            throw new IllegalArgumentException("로그인 실패 횟수 제한과 잠금 시간은 양수여야 합니다.");
        }
    }
}
