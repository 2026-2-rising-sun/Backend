package com.shoppinglive.common.security;

/**
 * Verifies access tokens issued by member-service. Every service validates tokens locally so that
 * a member-service outage cannot block authenticated traffic on the other services.
 */
public interface TokenValidator {

    AuthenticatedUser validate(String token) throws InvalidTokenException;
}
