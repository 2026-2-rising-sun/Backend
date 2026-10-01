package com.shoppinglive.common.security;

/**
 * Verifies the JWT locally and checks current session authorization. A Member status outage fails
 * closed with AccessSessionUnavailableException; public requests without credentials remain independent.
 */
public interface TokenValidator {

    AuthenticatedUser validate(String token) throws InvalidTokenException;
}
