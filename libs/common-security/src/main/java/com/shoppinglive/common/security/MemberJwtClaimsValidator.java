package com.shoppinglive.common.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** 추가 필수 claim을 검사한다. exp/nbf 시각 검사는 skew=0인 표준 validator가 함께 수행한다. */
final class MemberJwtClaimsValidator implements OAuth2TokenValidator<Jwt> {
    private static final Set<String> ROLES = Set.of("USER", "ADMIN");
    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "Invalid member access token", null);
    private final String audience;
    private final Clock clock;

    MemberJwtClaimsValidator(String audience, Clock clock) {
        this.audience = audience;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            String sub = jwt.getSubject();
            Instant issued = jwt.getIssuedAt();
            Instant expires = jwt.getExpiresAt();
            Object roles = jwt.getClaims().get("roles");
            Object jti = jwt.getClaims().get("jti");
            Object kid = jwt.getHeaders().get("kid");
            if (sub == null || !UUID.fromString(sub).toString().equals(sub)
                || issued == null || expires == null || !expires.isAfter(issued) || issued.isAfter(clock.instant())
                || !(jti instanceof String id) || id.isBlank()
                || !(kid instanceof String keyId) || keyId.isBlank()
                || !"RS256".equals(jwt.getHeaders().get("alg"))
                || jwt.getAudience() == null || !jwt.getAudience().contains(audience)
                || !(roles instanceof List<?> list) || list.isEmpty()
                || list.stream().anyMatch(role -> !(role instanceof String) || !ROLES.contains(role))) {
                return OAuth2TokenValidatorResult.failure(INVALID);
            }
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException exception) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
    }
}
