package com.shoppinglive.common.security;

import org.springframework.security.authentication.AuthenticationServiceException;

/** An unavailable authority cannot establish session activity; callers must fail closed with HTTP 503. */
public final class AccessSessionUnavailableException extends AuthenticationServiceException {
    public AccessSessionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
