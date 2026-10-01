package com.shoppinglive.member.auth.infrastructure;

import com.shoppinglive.common.security.AccessSessionUnavailableException;
import com.shoppinglive.common.security.AccessSessionVerifier;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Local DB implementation prevents Member's own authenticated requests from recursively calling HTTP. */
@Component
public class DatabaseAccessSessionVerifier implements AccessSessionVerifier {
    private final RefreshSessionRepository sessions;
    public DatabaseAccessSessionVerifier(RefreshSessionRepository sessions) { this.sessions = sessions; }

    @Override
    public boolean isActive(UUID memberId, UUID sessionId) {
        try { return sessions.isAccessActive(memberId, sessionId); }
        catch (DataAccessException exception) {
            throw new AccessSessionUnavailableException("회원 인증 상태를 확인할 수 없습니다.", exception);
        }
    }
}
