package com.shoppinglive.member.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties("member.auth")
public record MemberTokenProperties(
        Resource privateKeyLocation,
        String keyId,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {
    public MemberTokenProperties {
        if (privateKeyLocation == null
                || !privateKeyLocation.isFile()
                || keyId == null
                || keyId.isBlank()) {
            throw new IllegalArgumentException("회원 토큰 서명용 개인 키 파일과 키 ID는 필수입니다.");
        }
        requirePositiveSeconds(accessTokenTtl, "access-token-ttl");
        requirePositiveSeconds(refreshTokenTtl, "refresh-token-ttl");
    }

    private static void requirePositiveSeconds(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero() || value.getNano() != 0) {
            throw new IllegalArgumentException(field + "은 필수이며 양수의 정수 초 단위여야 합니다.");
        }
    }
}
