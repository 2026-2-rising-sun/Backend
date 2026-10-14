package com.shoppinglive.common.security;

import org.springframework.security.authentication.AuthenticationServiceException;

/** 검증 서비스가 응답하지 않으면 세션 활성 여부를 확인할 수 없으므로 HTTP 503으로 요청을 거부한다. */
public final class AccessSessionUnavailableException extends AuthenticationServiceException {
    public AccessSessionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
