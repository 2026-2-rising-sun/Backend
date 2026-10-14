package com.shoppinglive.common.security.test;

import com.shoppinglive.common.security.AccessSessionUnavailableException;
import com.shoppinglive.common.security.AccessSessionVerifier;
import java.util.UUID;

/** 운영 JAR에는 포함되지 않는 명시적인 도메인 테스트용 구현체다. JWT 검증은 실제로 수행한다. */
public final class StubAccessSessionVerifier implements AccessSessionVerifier {
    private volatile boolean active = true;
    private volatile boolean unavailable;

    public void reset() {
        active = true;
        unavailable = false;
    }

    public void revoke() {
        active = false;
    }

    public void fail() {
        unavailable = true;
    }

    @Override
    public boolean isActive(UUID memberId, UUID sessionId) {
        if (unavailable)
            throw new AccessSessionUnavailableException("테스트용 세션 상태 검증 서비스를 사용할 수 없습니다.", null);
        return active;
    }
}
