package com.shoppinglive.member.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties("member.auth")
public record MemberTokenProperties(Resource privateKeyLocation, String keyId,
                                    Duration accessTokenTtl, Duration refreshTokenTtl) {
    public MemberTokenProperties {
        if (privateKeyLocation == null || !privateKeyLocation.isFile() || keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Member signing private key file and key ID are required");
        }
        requirePositiveSeconds(accessTokenTtl, "access-token-ttl");
        requirePositiveSeconds(refreshTokenTtl, "refresh-token-ttl");
    }

    private static void requirePositiveSeconds(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero() || value.getNano() != 0) {
            throw new IllegalArgumentException(field + " is required and must be a positive whole number of seconds");
        }
    }
}
