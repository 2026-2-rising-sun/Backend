package com.shoppinglive.member.auth.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.shoppinglive.common.security.JwtVerificationProperties;
import com.shoppinglive.member.auth.config.MemberTokenProperties;
import com.shoppinglive.member.members.entity.Member;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

@Component
public class MemberTokenIssuer {
    private final JwtEncoder encoder;
    private final MemberTokenProperties tokens;
    private final JwtVerificationProperties verification;

    public MemberTokenIssuer(MemberTokenProperties tokens, JwtVerificationProperties verification)
            throws Exception {
        this.tokens = tokens;
        this.verification = verification;
        try (var privateInput = tokens.privateKeyLocation().getInputStream();
                var publicInput = verification.publicKeySetLocation().getInputStream()) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(privateInput);
            var publicKey = JWKSet.load(publicInput).getKeyByKeyId(tokens.keyId());
            if (!(publicKey instanceof RSAKey rsa)
                    || rsa.isPrivate()
                    || privateKey == null
                    || privateKey.getModulus().bitLength() < 2048
                    || !privateKey.getModulus().equals(rsa.toRSAPublicKey().getModulus())
                    || (privateKey instanceof RSAPrivateCrtKey crt
                            && !crt.getPublicExponent()
                                    .equals(rsa.toRSAPublicKey().getPublicExponent()))) {
                throw new IllegalArgumentException("회원 개인 키는 설정된 공개 JWKS의 키 ID와 일치해야 합니다.");
            }
            encoder =
                    new NimbusJwtEncoder(
                            new ImmutableJWKSet<>(
                                    new JWKSet(
                                            new RSAKey.Builder(rsa)
                                                    .privateKey(privateKey)
                                                    .build())));
        }
    }

    public String issue(Member member, UUID sessionId, Instant issuedAt) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer(verification.issuer())
                        .audience(List.of(verification.audience()))
                        .subject(member.getId().toString())
                        .issuedAt(issuedAt)
                        .expiresAt(issuedAt.plus(tokens.accessTokenTtl()))
                        .id(UUID.randomUUID().toString())
                        .claim("sid", sessionId.toString())
                        .claim("roles", List.copyOf(member.roles()))
                        .build();
        var header =
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(tokens.keyId()).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
