package com.shoppinglive.common.security;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.authentication.BadCredentialsException;

/** HTTP resource server와 동일한 decoder를 사용하는 기존 인터페이스의 실제 구현체. */
public final class JwtTokenValidator implements TokenValidator {
    private final JwtDecoder decoder;
    private final MemberJwtAuthenticationConverter converter;

    public JwtTokenValidator(JwtDecoder decoder, MemberJwtAuthenticationConverter converter) {
        this.decoder = decoder;
        this.converter = converter;
    }

    @Override
    public AuthenticatedUser validate(String token) throws InvalidTokenException {
        try {
            return (AuthenticatedUser) converter.convert(decoder.decode(token)).getPrincipal();
        } catch (JwtException | IllegalArgumentException | BadCredentialsException exception) {
            throw new InvalidTokenException("유효하지 않은 인증 토큰입니다.");
        }
    }
}
