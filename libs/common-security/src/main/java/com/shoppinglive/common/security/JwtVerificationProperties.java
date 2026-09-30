package com.shoppinglive.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

@ConfigurationProperties("shoppinglive.security.jwt")
public record JwtVerificationProperties(String issuer, String audience, Resource publicKeySetLocation) {
    public JwtVerificationProperties {
        if (!StringUtils.hasText(issuer) || !StringUtils.hasText(audience) || publicKeySetLocation == null) {
            throw new IllegalArgumentException("JWT issuer, audience and public-key-set-location are required");
        }
        if (!publicKeySetLocation.isFile()) {
            throw new IllegalArgumentException("JWT public-key-set-location must be a local file");
        }
    }
}
