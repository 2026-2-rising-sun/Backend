package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.member.operations.SellerBootstrapCommand;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServerFactory;

class SellerBootstrapCommandTest {
    @Test
    void commandContextHasNoHttpServerOrMemberApplicationScan() {
        try (var context = SellerBootstrapCommand.openNonWebContext()) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(WebServerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(MemberServiceApplication.class)).isEmpty();
            assertThat(context.getBean(SellerBootstrapCommand.class)).isNotNull();
        }
    }

    @Test
    void rejectsMissingSettingsAndInvalidInputWithoutExposingSecrets() {
        var command = new SellerBootstrapCommand();
        assertThatThrownBy(() -> command.execute(Map.of())).isInstanceOf(IllegalArgumentException.class);
        var environment = new HashMap<>(Map.of("MEMBER_BOOTSTRAP_DB_URL", "jdbc:postgresql://unused/test",
            "MEMBER_BOOTSTRAP_DB_SCHEMA", "public", "MEMBER_BOOTSTRAP_DB_USER", "operator",
            "MEMBER_BOOTSTRAP_DB_PASSWORD", "database-secret", "MEMBER_BOOTSTRAP_SELLER_EMAIL", "bad-email",
            "MEMBER_BOOTSTRAP_SELLER_PASSWORD", "secret-password", "MEMBER_BOOTSTRAP_SELLER_DISPLAY_NAME", "admin"));
        assertThatThrownBy(() -> command.execute(environment)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid seller email, display name or password");
        assertThatThrownBy(() -> MemberServiceApplication.main(new String[] {"--bootstrap-seller", "unexpected"}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
