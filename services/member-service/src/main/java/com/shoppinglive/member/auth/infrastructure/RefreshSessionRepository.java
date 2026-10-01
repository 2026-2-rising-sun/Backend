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
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Call mutations only after locking the member, then the family. Never persist raw refresh tokens. */
@Repository
public class RefreshSessionRepository {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public RefreshSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public SessionToken create(UUID memberId, Instant now, Instant expiresAt) {
        UUID familyId = UUID.randomUUID();
        jdbc.update("INSERT INTO refresh_families(id,member_id,created_at,expires_at) VALUES (?,?,?,?)",
            familyId, memberId, Timestamp.from(now), Timestamp.from(expiresAt));
        return insertToken(familyId, now);
    }

    private SessionToken insertToken(UUID familyId, Instant now) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String rawToken = familyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update("INSERT INTO refresh_tokens(token_hash,family_id,created_at) VALUES (?,?,?)",
            hash(rawToken), familyId, Timestamp.from(now));
        return new SessionToken(familyId, rawToken);
    }

    public Optional<StoredToken> find(String rawToken) {
        return jdbc.query("""
            SELECT f.id,f.member_id,t.used_at FROM refresh_tokens t
            JOIN refresh_families f ON f.id=t.family_id WHERE t.token_hash=?
            """, (rs, row) -> new StoredToken(rs.getObject("id", UUID.class),
                rs.getObject("member_id", UUID.class), instant(rs.getTimestamp("used_at"))), hash(rawToken)).stream().findFirst();
    }

    public Family lock(UUID id) {
        return jdbc.queryForObject("SELECT * FROM refresh_families WHERE id=? FOR UPDATE", (rs, row) ->
            new Family(id, rs.getTimestamp("expires_at").toInstant(), instant(rs.getTimestamp("revoked_at")),
                instant(rs.getTimestamp("security_revoked_at"))), id);
    }

    public SessionToken rotate(String oldToken, UUID familyId, Instant now) {
        int updated = jdbc.update("UPDATE refresh_tokens SET used_at=? WHERE token_hash=? AND family_id=? AND used_at IS NULL",
            Timestamp.from(now), hash(oldToken), familyId);
        if (updated != 1) throw new IllegalStateException("Refresh token was not exclusively locked");
        return insertToken(familyId, now);
    }

    public void revoke(UUID familyId, Instant now, boolean security) {
        jdbc.update("UPDATE refresh_families SET revoked_at=COALESCE(revoked_at,?)"
            + (security ? ",security_revoked_at=COALESCE(security_revoked_at,?)" : "") + " WHERE id=?",
            security ? new Object[] {Timestamp.from(now), Timestamp.from(now), familyId}
                : new Object[] {Timestamp.from(now), familyId});
    }

    public void revokeAll(UUID memberId, Instant now) {
        jdbc.update("""
            UPDATE refresh_families SET revoked_at=COALESCE(revoked_at,?),
                security_revoked_at=COALESCE(security_revoked_at,?) WHERE member_id=?
            """, Timestamp.from(now), Timestamp.from(now), memberId);
    }

    /** Refresh expiry and ordinary logout do not shorten already issued access tokens. */
    public boolean isAccessActive(UUID memberId, UUID sessionId) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM refresh_families f JOIN members m ON m.id=f.member_id
            WHERE f.id=? AND f.member_id=? AND f.security_revoked_at IS NULL AND m.withdrawn_at IS NULL
            """, Integer.class, sessionId, memberId);
        return count != null && count == 1;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    public record SessionToken(UUID familyId, String rawToken) {
        @Override public String toString() { return "SessionToken[REDACTED]"; }
    }
    public record StoredToken(UUID familyId, UUID memberId, Instant usedAt) { }
    public record Family(UUID id, Instant expiresAt, Instant revokedAt, Instant securityRevokedAt) { }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
