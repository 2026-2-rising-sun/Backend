package com.shoppinglive.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

@ConfigurationProperties("shoppinglive.security.jwt")
public record JwtVerificationProperties(
        String issuer, String audience, Resource publicKeySetLocation) {
    public JwtVerificationProperties {
        if (!StringUtils.hasText(issuer)
                || !StringUtils.hasText(audience)
                || publicKeySetLocation == null) {
            throw new IllegalArgumentException(
                    "JWT issuer, audience, public-key-set-location 설정이 필요합니다.");
        }
        if (!publicKeySetLocation.isFile()) {
            throw new IllegalArgumentException("JWT public-key-set-location은 로컬 파일이어야 합니다.");
        }
    }
}
