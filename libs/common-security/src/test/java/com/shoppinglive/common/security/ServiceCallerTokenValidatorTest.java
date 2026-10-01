package com.shoppinglive.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

class ServiceCallerTokenValidatorTest {
    private static final String TOKEN = "only-a-test-credential-with-more-than-32-characters";

    @Test
    void identifiesCallerOnlyFromConfiguredKeyAndRejectsUnknownCredentials() {
        var validator = new ServiceCallerTokenValidator(Map.of("shopping", TOKEN));
        assertThat(validator.validate(TOKEN).serviceId()).isEqualTo("shopping");
        for (var invalid : new String[] {null, "", "shopping", TOKEN + "modified"}) {
            assertThatThrownBy(() -> validator.validate(invalid)).isInstanceOf(BadCredentialsException.class);
        }
    }

    @Test
    void rejectsAmbiguousAndWeakConfiguration() {
        for (var config : java.util.List.of(Map.of("shopping", TOKEN, "live", TOKEN),
            Map.of("shopping", "short"), Map.of("ADMIN", TOKEN), Map.of("shopping", " " + TOKEN))) {
            assertThatThrownBy(() -> new ServiceCallerTokenValidator(config)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
