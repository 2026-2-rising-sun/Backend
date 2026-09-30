package com.shoppinglive.member.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 초기 세션 저장소. 원문 refresh는 반환만 하고 DB에는 hash만 기록한다. */
@Repository
public class RefreshSessionRepository {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public RefreshSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String create(UUID memberId, Instant now, Instant expiresAt) {
        UUID familyId = UUID.randomUUID();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String rawToken = familyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update("INSERT INTO refresh_families(id,member_id,created_at,expires_at) VALUES (?,?,?,?)",
            familyId, memberId, Timestamp.from(now), Timestamp.from(expiresAt));
        jdbc.update("INSERT INTO refresh_tokens(token_hash,family_id,created_at) VALUES (?,?,?)",
            hash(rawToken), familyId, Timestamp.from(now));
        return rawToken;
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
