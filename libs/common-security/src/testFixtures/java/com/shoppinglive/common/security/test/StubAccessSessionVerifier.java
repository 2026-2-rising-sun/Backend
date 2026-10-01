package com.shoppinglive.common.security.test;

import com.shoppinglive.common.security.AccessSessionUnavailableException;
import com.shoppinglive.common.security.AccessSessionVerifier;
import java.util.UUID;

/** Explicit domain-test fixture, never included in production JARs. JWT verification stays real. */
public final class StubAccessSessionVerifier implements AccessSessionVerifier {
    private volatile boolean active = true;
    private volatile boolean unavailable;

    public void reset() { active = true; unavailable = false; }
    public void revoke() { active = false; }
    public void fail() { unavailable = true; }

    @Override public boolean isActive(UUID memberId, UUID sessionId) {
        if (unavailable) throw new AccessSessionUnavailableException("Test status authority unavailable", null);
        return active;
    }
}
