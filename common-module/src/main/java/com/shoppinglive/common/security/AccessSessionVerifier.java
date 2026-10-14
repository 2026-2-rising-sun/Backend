package com.shoppinglive.common.security;

import java.util.UUID;

/** 로컬 JWT 검증 후 현재 접근 권한을 확인한다. 구현체는 활성 상태 검증 결과를 캐시하지 않는다. */
@FunctionalInterface
public interface AccessSessionVerifier {
    boolean isActive(UUID memberId, UUID sessionId) throws AccessSessionUnavailableException;
}
