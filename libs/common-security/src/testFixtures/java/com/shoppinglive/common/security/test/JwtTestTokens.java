package com.shoppinglive.common.security.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 매 생성마다 새 RSA 키를 만든다. testFixtures이므로 운영 라이브러리/JAR에 포함되지 않는다. */
public final class JwtTestTokens {
    public static final String ISSUER = "shoppinglive-member";
    public static final String AUDIENCE = "shoppinglive-api";
    public static final String MEMBER_A = "00000000-0000-4000-8000-000000000001";
    public static final String MEMBER_B = "00000000-0000-4000-8000-000000000002";
    public static final String ADMIN = "00000000-0000-4000-8000-000000000003";
    private final RSAKey key;

    public JwtTestTokens() {
        try {
            key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot generate test RSA key", exception);
        }
    }

    public RSAKey publicKey() { return key.toPublicJWK(); }
    public JWKSet publicJwkSet() { return new JWKSet(publicKey()); }
    public String keyId() { return key.getKeyID(); }

    public JWTClaimsSet.Builder claims(String memberId, Set<String> roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().subject(memberId).issuer(ISSUER).audience(AUDIENCE)
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString()).claim("roles", List.copyOf(roles))
            .claim("sid", UUID.randomUUID().toString());
    }

    public String token(String memberId, Set<String> roles) { return sign(claims(memberId, roles).build()); }

    public String sign(JWTClaimsSet claims) {
        return sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId()).build(), claims);
    }

    public String sign(JWSHeader header, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot sign test JWT", exception);
        }
    }

    /** Member 발급기 테스트에만 사용하는 런타임 생성 개인키다. 운영 소스/JAR에는 포함되지 않는다. */
    public byte[] privateKeyPkcs8() throws JOSEException { return key.toRSAPrivateKey().getEncoded(); }
}
