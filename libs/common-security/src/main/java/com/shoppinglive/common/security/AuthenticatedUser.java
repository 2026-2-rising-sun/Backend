package com.shoppinglive.common.security;

import java.time.Instant;
import java.util.Set;

public record AuthenticatedUser(String memberId, Set<String> roles, Instant expiresAt) {
}
