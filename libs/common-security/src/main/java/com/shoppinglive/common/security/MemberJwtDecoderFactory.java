package com.shoppinglive.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** 디스크에서 주입한 공개 JWKS만 사용한다. Member 조회와 원격 키 discovery가 없다. */
public final class MemberJwtDecoderFactory {
    private MemberJwtDecoderFactory() { }

    public static JwtDecoder create(JWKSet publicKeys, String issuer, String audience) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT issuer and audience are required");
        }
        Set<String> ids = new HashSet<>();
        for (JWK key : publicKeys.getKeys()) {
            if (!(key instanceof RSAKey rsa) || rsa.isPrivate() || rsa.size() < 2048
                || key.getKeyID() == null || key.getKeyID().isBlank() || !ids.add(key.getKeyID())
                || (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse()))
                || (key.getKeyOperations() != null && !key.getKeyOperations().contains(KeyOperation.VERIFY))
                || (key.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(key.getAlgorithm()))) {
                throw new IllegalArgumentException("JWKS requires unique kid and public RSA keys of at least 2048 bits");
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("JWT public key set must not be empty");
        }
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
            new ImmutableJWKSet<>(publicKeys)));
        // Spring validators below own claim/time validation; signature verification remains in Nimbus.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(issuer),
            new MemberJwtClaimsValidator(audience, Clock.systemUTC())));
        return decoder;
    }
}
