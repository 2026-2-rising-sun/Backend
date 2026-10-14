package com.shoppinglive.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

class ServiceCallerTokenValidatorTest {
    private static final String TOKEN = "only-a-test-credential-with-more-than-32-characters";

    @Test
    @DisplayName("설정된 키로만 호출 서비스를 식별하고 알 수 없는 인증 정보는 거절한다")
    void identifiesCallerOnlyFromConfiguredKeyAndRejectsUnknownCredentials() {
        var validator = new ServiceCallerTokenValidator(Map.of("shopping", TOKEN));
        assertThat(validator.validate(TOKEN).serviceId()).isEqualTo("shopping");
        for (var invalid : new String[] {null, "", "shopping", TOKEN + "modified"}) {
            assertThatThrownBy(() -> validator.validate(invalid))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    @Test
    @DisplayName("중복되거나 취약한 서비스 인증 설정을 거절한다")
    void rejectsAmbiguousAndWeakConfiguration() {
        for (var config :
                java.util.List.of(
                        Map.of("shopping", TOKEN, "live", TOKEN),
                        Map.of("shopping", "short"),
                        Map.of("SELLER", TOKEN),
                        Map.of("shopping", " " + TOKEN))) {
            assertThatThrownBy(() -> new ServiceCallerTokenValidator(config))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
