package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.member.operations.AdminBootstrapCommand;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServerFactory;

class AdminBootstrapCommandTest {
    @Test
    void commandContextHasNoHttpServerOrMemberApplicationScan() {
        try (var context = AdminBootstrapCommand.openNonWebContext()) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(WebServerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(MemberServiceApplication.class)).isEmpty();
            assertThat(context.getBean(AdminBootstrapCommand.class)).isNotNull();
        }
    }

    @Test
    void rejectsMissingSettingsAndInvalidInputWithoutExposingSecrets() {
        var command = new AdminBootstrapCommand();
        assertThatThrownBy(() -> command.execute(Map.of())).isInstanceOf(IllegalArgumentException.class);
        var environment = new HashMap<>(Map.of("MEMBER_BOOTSTRAP_DB_URL", "jdbc:postgresql://unused/test",
            "MEMBER_BOOTSTRAP_DB_SCHEMA", "public", "MEMBER_BOOTSTRAP_DB_USER", "operator",
            "MEMBER_BOOTSTRAP_DB_PASSWORD", "database-secret", "MEMBER_BOOTSTRAP_ADMIN_EMAIL", "bad-email",
            "MEMBER_BOOTSTRAP_ADMIN_PASSWORD", "secret-password", "MEMBER_BOOTSTRAP_ADMIN_DISPLAY_NAME", "admin"));
        assertThatThrownBy(() -> command.execute(environment)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid admin email, display name or password");
        assertThatThrownBy(() -> MemberServiceApplication.main(new String[] {"--bootstrap-admin", "unexpected"}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
