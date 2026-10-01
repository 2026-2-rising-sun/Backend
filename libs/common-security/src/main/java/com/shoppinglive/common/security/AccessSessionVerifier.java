package com.shoppinglive.common.security;

import java.util.UUID;

/** Checks current access authorization after local JWT validation. Implementations must not cache active results. */
@FunctionalInterface
public interface AccessSessionVerifier {
    boolean isActive(UUID memberId, UUID sessionId) throws AccessSessionUnavailableException;
}
