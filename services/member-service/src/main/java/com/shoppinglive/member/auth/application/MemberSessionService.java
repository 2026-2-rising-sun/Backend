package com.shoppinglive.member.auth.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.auth.config.MemberTokenProperties;
import com.shoppinglive.member.auth.infrastructure.RefreshSessionRepository;
import com.shoppinglive.member.members.infrastructure.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemberSessionService {
    private final MemberRepository members;
    private final RefreshSessionRepository sessions;
    private final MemberTokenIssuer issuer;
    private final MemberTokenProperties policy;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MemberSessionService(MemberRepository members, RefreshSessionRepository sessions, MemberTokenIssuer issuer,
                                MemberTokenProperties policy, PasswordEncoder passwords, Clock clock,
                                PlatformTransactionManager transactions) {
        this.members = members; this.sessions = sessions; this.issuer = issuer;
        this.policy = policy; this.passwords = passwords; this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    public TokenPair refresh(String rawToken) {
        TokenPair result = transaction.execute(status -> {
            var token = sessions.find(rawToken).orElse(null);
            // A guessed family ID or a forged hash must never revoke a real family.
            if (token == null) return null;
            var member = members.findForUpdate(token.memberId()).orElseThrow();
            var family = sessions.lock(token.familyId());
            var current = sessions.find(rawToken).orElseThrow();
            Instant now = now();
            if (current.usedAt() != null) {
                sessions.revoke(family.id(), now, true);
                return null;
            }
            if (member.isWithdrawn() || family.revokedAt() != null || family.securityRevokedAt() != null
                || !family.expiresAt().isAfter(now)) return null;
            var next = sessions.rotate(rawToken, family.id(), now);
            String access = issuer.issue(member, family.id(), now);
            return new TokenPair(access, "Bearer", policy.accessTokenTtl().toSeconds(), next.rawToken());
        });
        // Reuse revocation must commit before returning 401, rather than rolling back with the exception.
        if (result == null) throw unauthorized();
        return result;
    }

    public void logout(String rawToken) {
        transaction.executeWithoutResult(status -> {
            var token = sessions.find(rawToken).orElse(null);
            if (token == null) return;
            members.findForUpdate(token.memberId()).orElseThrow();
            sessions.lock(token.familyId());
            sessions.revoke(token.familyId(), now(), false);
        });
    }

    public void withdraw(UUID memberId, String password) {
        transaction.executeWithoutResult(status -> {
            var member = members.findForUpdate(memberId).orElseThrow(MemberSessionService::unauthorized);
            if (member.isWithdrawn() || !passwords.matches(password, member.getPasswordHash())) throw unauthorized();
            Instant now = now();
            member.withdraw(now);
            sessions.revokeAll(memberId, now);
        });
    }

    public void revokeAll(UUID memberId) {
        transaction.executeWithoutResult(status -> {
            members.findForUpdate(memberId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
            sessions.revokeAll(memberId, now());
        });
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.SECONDS); }
    private static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
    }
}
