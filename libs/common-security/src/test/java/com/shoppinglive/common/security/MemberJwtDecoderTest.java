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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class MemberJwtDecoderTest {
    private static final JwtTestTokens TOKENS = new JwtTestTokens();
    private final JwtDecoder decoder = MemberJwtDecoderFactory.create(TOKENS.publicJwkSet(),
        JwtTestTokens.ISSUER, JwtTestTokens.AUDIENCE);

    @Test
    void verifiesSignatureAndCreatesImmutableDomainPrincipal() {
        var validator = new JwtTokenValidator(decoder, new MemberJwtAuthenticationConverter((member, session) -> true));
        var user = validator.validate(TOKENS.token(JwtTestTokens.MEMBER_A, Set.of("USER", "SELLER")));
        assertThat(user.memberId()).isEqualTo(JwtTestTokens.MEMBER_A);
        assertThat(user.roles()).containsExactlyInAnyOrder("USER", "SELLER");
        assertThatThrownBy(() -> user.roles().add("OTHER")).isInstanceOf(UnsupportedOperationException.class);
        var authentication = new MemberJwtAuthenticationConverter((member, session) -> true).convert(
            decoder.decode(TOKENS.token(JwtTestTokens.SELLER, Set.of("SELLER"))));
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_SELLER");
        assertThat(authentication.getCredentials()).isEqualTo("");
    }

    @Test
    void tokenValidatorCannotBypassSessionChecksAndInvalidJwtNeverCallsAuthority() {
        String sessionId = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        var converter = new MemberJwtAuthenticationConverter((member, session) -> {
            calls.incrementAndGet();
            assertThat(member.toString()).isEqualTo(JwtTestTokens.MEMBER_A);
            assertThat(session.toString()).isEqualTo(sessionId);
            return false;
        });
        var validator = new JwtTokenValidator(decoder, converter);
        String token = TOKENS.sign(claims().claim("sid", sessionId).build());
        assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> validator.validate("invalid")).isInstanceOf(InvalidTokenException.class);
        assertThat(calls.get()).isEqualTo(1);
        var unavailable = new JwtTokenValidator(decoder, new MemberJwtAuthenticationConverter((member, session) -> {
            throw new AccessSessionUnavailableException("unavailable", null);
        }));
        assertThatThrownBy(() -> unavailable.validate(token)).isInstanceOf(AccessSessionUnavailableException.class);
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidRequiredClaims() {
        Instant now = Instant.now();
        return Stream.of(
            bad("missing subject", claims().subject(null)),
            bad("missing sid", claims().claim("sid", null)),
            bad("malformed sid", claims().claim("sid", "1-1-1-1-1")),
            bad("non-string sid", claims().claim("sid", 1)),
            bad("malformed UUID", claims().subject("1-1-1-1-1")),
            bad("wrong issuer", claims().issuer("another-member")),
            bad("wrong audience", claims().audience("other-api")),
            bad("missing audience", claims().audience((String) null)),
            bad("missing expiry", claims().expirationTime(null)),
            bad("expired by one second without skew", claims().issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.minusSeconds(1)))),
            bad("missing issued at", claims().issueTime(null)),
            bad("future issued at", claims().issueTime(Date.from(now.plusSeconds(60)))),
            bad("expiry before issued at", claims().expirationTime(Date.from(now.minusSeconds(60)))),
            bad("expiry equal issued at", claims().issueTime(Date.from(now)).expirationTime(Date.from(now))),
            bad("future not before", claims().notBeforeTime(Date.from(now.plusSeconds(60)))),
            bad("missing jti", claims().jwtID(null)),
            bad("blank jti", claims().jwtID(" ")),
            bad("missing roles", claims().claim("roles", null)),
            bad("empty roles", claims().claim("roles", List.of())),
            bad("unknown role", claims().claim("roles", List.of("USER", "ADMIN"))),
            bad("roles is not array", claims().claim("roles", "USER")),
            bad("role is not string", claims().claim("roles", List.of(1)))
        );
    }

    @Test
    void rejectsMissingUnknownKeyIdsAndWrongSignatures() {
        assertRejected(TOKENS.sign(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims().build()));
        assertRejected(TOKENS.sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unknown").build(), claims().build()));
        var other = new JwtTestTokens();
        assertRejected(other.sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TOKENS.keyId()).build(), claims().build()));
    }

    @Test
    void rejectsNonRs256UnsignedAndMalformedTokens() throws Exception {
        assertRejected(TOKENS.sign(new JWSHeader.Builder(JWSAlgorithm.RS512).keyID(TOKENS.keyId()).build(), claims().build()));
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(TOKENS.keyId()).build(), claims().build());
        hmac.sign(new MACSigner(new byte[32]));
        assertRejected(hmac.serialize());
        assertRejected(new PlainJWT(claims().build()).serialize());
        assertThatThrownBy(() -> new JwtTokenValidator(decoder, new MemberJwtAuthenticationConverter((member, session) -> true)).validate("not-a-token"))
            .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void supportsOldAndNewPublicKeysDuringRotation() {
        var next = new JwtTestTokens();
        var rotating = MemberJwtDecoderFactory.create(new JWKSet(List.of(TOKENS.publicKey(), next.publicKey())),
            JwtTestTokens.ISSUER, JwtTestTokens.AUDIENCE);
        assertThat(rotating.decode(TOKENS.token(JwtTestTokens.MEMBER_A, Set.of("USER"))).getSubject())
            .isEqualTo(JwtTestTokens.MEMBER_A);
        assertThat(rotating.decode(next.token(JwtTestTokens.MEMBER_B, Set.of("USER"))).getSubject())
            .isEqualTo(JwtTestTokens.MEMBER_B);
    }

    @Test
    void rejectsUnsafeKeyConfigurationBeforeServingRequests() throws Exception {
        RSAKey privateKey = new RSAKeyGenerator(2048).keyID("private").generate();
        List<JWKSet> unsafe = List.of(new JWKSet(), new JWKSet(privateKey),
            new JWKSet(List.of(TOKENS.publicKey(), TOKENS.publicKey())),
            new JWKSet(new RSAKey.Builder(TOKENS.publicKey()).keyID(null).build()),
            new JWKSet(new RSAKey.Builder(TOKENS.publicKey()).keyUse(KeyUse.ENCRYPTION).build()),
            new JWKSet(new RSAKey.Builder(TOKENS.publicKey()).algorithm(JWSAlgorithm.RS512).build()));
        for (var keys : unsafe) {
            assertThatThrownBy(() -> MemberJwtDecoderFactory.create(keys, JwtTestTokens.ISSUER, JwtTestTokens.AUDIENCE))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private JWTClaimsSet.Builder claims() { return TOKENS.claims(JwtTestTokens.MEMBER_A, Set.of("USER")); }
    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
    private DynamicTest bad(String reason, JWTClaimsSet.Builder claims) {
        return DynamicTest.dynamicTest(reason, () -> assertRejected(TOKENS.sign(claims.build())));
    }
}
