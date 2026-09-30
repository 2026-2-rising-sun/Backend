package com.shoppinglive.member.auth.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.auth.config.MemberLoginProperties;
import com.shoppinglive.member.auth.config.MemberTokenProperties;
import com.shoppinglive.member.auth.infrastructure.RefreshSessionRepository;
import com.shoppinglive.member.members.domain.Member;
import com.shoppinglive.member.members.infrastructure.MemberRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemberLoginService {
    private final MemberRepository members;
    private final PasswordEncoder passwords;
    private final MemberTokenIssuer issuer;
    private final RefreshSessionRepository sessions;
    private final MemberTokenProperties tokens;
    private final MemberLoginProperties policy;
    private final TransactionTemplate transaction;
    private final String dummyHash;

    public MemberLoginService(MemberRepository members, PasswordEncoder passwords, MemberTokenIssuer issuer,
                              RefreshSessionRepository sessions, MemberTokenProperties tokens,
                              MemberLoginProperties policy, PlatformTransactionManager transactions) {
        this.members = members;
        this.passwords = passwords;
        this.issuer = issuer;
        this.sessions = sessions;
        this.tokens = tokens;
        this.policy = policy;
        this.transaction = new TransactionTemplate(transactions);
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public TokenPair login(String email, String password) {
        // 실패 횟수 변경도 커밋된 뒤 401을 만든다. 예외 rollback으로 잠금이 유실되지 않는다.
        TokenPair result = transaction.execute(status -> loginLocked(email, password));
        if (result == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
        return result;
    }

    private TokenPair loginLocked(String email, String password) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Member member = members.findForLogin(Member.normalizeEmail(email)).orElse(null);
        if (member == null || member.loginLocked(now)) {
            passwords.matches(password, dummyHash);
            return null;
        }
        if (!passwords.matches(password, member.getPasswordHash())) {
            member.recordLoginFailure(now, policy.maxFailedAttempts(), policy.lockDuration());
            return null;
        }
        member.recordLoginSuccess();
        String access = issuer.issue(member, now);
        String refresh = sessions.create(member.getId(), now, now.plus(tokens.refreshTokenTtl()));
        return new TokenPair(access, "Bearer", tokens.accessTokenTtl().toSeconds(), refresh);
    }
}
