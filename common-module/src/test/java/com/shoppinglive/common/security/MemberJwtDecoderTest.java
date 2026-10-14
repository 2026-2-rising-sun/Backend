package com.shoppinglive.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.shoppinglive.common.security.test.JwtTestTokens;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class MemberJwtDecoderTest {
    private static final JwtTestTokens TOKENS = new JwtTestTokens();
    private final JwtDecoder decoder =
            MemberJwtDecoderFactory.create(
                    TOKENS.publicJwkSet(), JwtTestTokens.ISSUER, JwtTestTokens.AUDIENCE);

    @Test
    @DisplayName("JWT 서명을 검증하고 변경할 수 없는 인증 사용자 정보를 만든다")
    void verifiesSignatureAndCreatesImmutableDomainPrincipal() {
        var converter = new MemberJwtAuthenticationConverter((member, session) -> true);
        var user =
                (AuthenticatedUser)
                        converter
                                .convert(
                                        decoder.decode(
                                                TOKENS.token(
                                                        JwtTestTokens.MEMBER_A,
                                                        Set.of("USER", "SELLER"))))
                                .getPrincipal();
        assertThat(user.memberId()).isEqualTo(JwtTestTokens.MEMBER_A);
        assertThat(user.roles()).containsExactlyInAnyOrder("USER", "SELLER");
        assertThatThrownBy(() -> user.roles().add("OTHER"))
                .isInstanceOf(UnsupportedOperationException.class);
        var authentication =
                new MemberJwtAuthenticationConverter((member, session) -> true)
                        .convert(
                                decoder.decode(
                                        TOKENS.token(JwtTestTokens.SELLER, Set.of("SELLER"))));
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_SELLER");
        assertThat(authentication.getCredentials()).isEqualTo("");
    }

    @Test
    @DisplayName("인증 시 세션을 확인하고 잘못된 JWT는 세션 조회 전에 거절한다")
    void authenticationChecksSessionsAndInvalidJwtNeverCallsAuthority() {
        String sessionId = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        var converter =
                new MemberJwtAuthenticationConverter(
                        (member, session) -> {
                            calls.incrementAndGet();
                            assertThat(member.toString()).isEqualTo(JwtTestTokens.MEMBER_A);
                            assertThat(session.toString()).isEqualTo(sessionId);
                            return false;
                        });
        String token = TOKENS.sign(claims().claim("sid", sessionId).build());
        assertThatThrownBy(() -> converter.convert(decoder.decode(token)))
                .isInstanceOf(
                        org.springframework.security.authentication.BadCredentialsException.class);
        assertThatThrownBy(() -> converter.convert(decoder.decode("invalid")))
                .isInstanceOf(JwtException.class);
        assertThat(calls.get()).isEqualTo(1);
        var unavailable =
                new MemberJwtAuthenticationConverter(
                        (member, session) -> {
                            throw new AccessSessionUnavailableException("unavailable", null);
                        });
        assertThatThrownBy(() -> unavailable.convert(decoder.decode(token)))
                .isInstanceOf(AccessSessionUnavailableException.class);
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidRequiredClaims() {
        Instant now = Instant.now();
        return Stream.of(
                bad("subject 누락", claims().subject(null)),
                bad("sid 누락", claims().claim("sid", null)),
                bad("잘못된 sid 형식", claims().claim("sid", "1-1-1-1-1")),
                bad("문자열이 아닌 sid", claims().claim("sid", 1)),
                bad("잘못된 UUID 형식", claims().subject("1-1-1-1-1")),
                bad("issuer 불일치", claims().issuer("another-member")),
                bad("audience 불일치", claims().audience("other-api")),
                bad("audience 누락", claims().audience((String) null)),
                bad("만료 시각 누락", claims().expirationTime(null)),
                bad(
                        "시각 오차 허용 없이 1초 전에 만료",
                        claims().issueTime(Date.from(now.minusSeconds(10)))
                                .expirationTime(Date.from(now.minusSeconds(1)))),
                bad("발급 시각 누락", claims().issueTime(null)),
                bad("미래 발급 시각", claims().issueTime(Date.from(now.plusSeconds(60)))),
                bad("발급 시각 이전의 만료 시각", claims().expirationTime(Date.from(now.minusSeconds(60)))),
                bad(
                        "발급 시각과 같은 만료 시각",
                        claims().issueTime(Date.from(now)).expirationTime(Date.from(now))),
                bad("미래 활성 시작 시각", claims().notBeforeTime(Date.from(now.plusSeconds(60)))),
                bad("jti 누락", claims().jwtID(null)),
                bad("빈 jti", claims().jwtID(" ")),
                bad("roles 누락", claims().claim("roles", null)),
                bad("빈 roles", claims().claim("roles", List.of())),
                bad("알 수 없는 역할", claims().claim("roles", List.of("USER", "ADMIN"))),
                bad("배열이 아닌 roles", claims().claim("roles", "USER")),
                bad("문자열이 아닌 역할", claims().claim("roles", List.of(1))));
    }

    @Test
    @DisplayName("누락되거나 알 수 없는 키와 잘못된 서명을 거절한다")
    void rejectsMissingUnknownKeyIdsAndWrongSignatures() {
        assertRejected(
                TOKENS.sign(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims().build()));
        assertRejected(
                TOKENS.sign(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unknown").build(),
                        claims().build()));
        var other = new JwtTestTokens();
        assertRejected(
                other.sign(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TOKENS.keyId()).build(),
                        claims().build()));
    }

    @Test
    @DisplayName("RS256 이외의 알고리즘과 서명 없는 토큰 및 손상된 토큰을 거절한다")
    void rejectsNonRs256UnsignedAndMalformedTokens() throws Exception {
        assertRejected(
                TOKENS.sign(
                        new JWSHeader.Builder(JWSAlgorithm.RS512).keyID(TOKENS.keyId()).build(),
                        claims().build()));
        SignedJWT hmac =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(TOKENS.keyId()).build(),
                        claims().build());
        hmac.sign(new MACSigner(new byte[32]));
        assertRejected(hmac.serialize());
        assertRejected(new PlainJWT(claims().build()).serialize());
        assertThatThrownBy(() -> decoder.decode("not-a-token")).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("키 교체 중 기존 공개 키와 새 공개 키를 함께 지원한다")
    void supportsOldAndNewPublicKeysDuringRotation() {
        var next = new JwtTestTokens();
        var rotating =
                MemberJwtDecoderFactory.create(
                        new JWKSet(List.of(TOKENS.publicKey(), next.publicKey())),
                        JwtTestTokens.ISSUER,
                        JwtTestTokens.AUDIENCE);
        assertThat(
                        rotating.decode(TOKENS.token(JwtTestTokens.MEMBER_A, Set.of("USER")))
                                .getSubject())
                .isEqualTo(JwtTestTokens.MEMBER_A);
        assertThat(rotating.decode(next.token(JwtTestTokens.MEMBER_B, Set.of("USER"))).getSubject())
                .isEqualTo(JwtTestTokens.MEMBER_B);
    }

    @Test
    @DisplayName("안전하지 않은 키 설정은 요청 처리 전에 거절한다")
    void rejectsUnsafeKeyConfigurationBeforeServingRequests() throws Exception {
        RSAKey privateKey = new RSAKeyGenerator(2048).keyID("private").generate();
        List<JWKSet> unsafe =
                List.of(
                        new JWKSet(),
                        new JWKSet(privateKey),
                        new JWKSet(List.of(TOKENS.publicKey(), TOKENS.publicKey())),
                        new JWKSet(new RSAKey.Builder(TOKENS.publicKey()).keyID(null).build()),
                        new JWKSet(
                                new RSAKey.Builder(TOKENS.publicKey())
                                        .keyUse(KeyUse.ENCRYPTION)
                                        .build()),
                        new JWKSet(
                                new RSAKey.Builder(TOKENS.publicKey())
                                        .algorithm(JWSAlgorithm.RS512)
                                        .build()));
        for (var keys : unsafe) {
            assertThatThrownBy(
                            () ->
                                    MemberJwtDecoderFactory.create(
                                            keys, JwtTestTokens.ISSUER, JwtTestTokens.AUDIENCE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private JWTClaimsSet.Builder claims() {
        return TOKENS.claims(JwtTestTokens.MEMBER_A, Set.of("USER"));
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private DynamicTest bad(String reason, JWTClaimsSet.Builder claims) {
        return DynamicTest.dynamicTest(reason, () -> assertRejected(TOKENS.sign(claims.build())));
    }
}
