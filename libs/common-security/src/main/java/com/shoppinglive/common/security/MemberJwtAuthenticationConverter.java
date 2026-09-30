package com.shoppinglive.common.security;

import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** oauth2ResourceServer의 검증된 JWT를 기존 도메인 principal 계약으로 변환한다. */
public final class MemberJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AuthenticatedUser user = new AuthenticatedUser(jwt.getSubject(),
            Set.copyOf(jwt.getClaimAsStringList("roles")), jwt.getExpiresAt());
        return new MemberAuthentication(user);
    }

    private static final class MemberAuthentication extends AbstractAuthenticationToken {
        private final AuthenticatedUser user;

        private MemberAuthentication(AuthenticatedUser user) {
            super(user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
            this.user = user;
            super.setAuthenticated(true);
        }

        @Override public Object getCredentials() { return ""; }
        @Override public AuthenticatedUser getPrincipal() { return user; }
        @Override public String getName() { return user.memberId(); }
    }
}
