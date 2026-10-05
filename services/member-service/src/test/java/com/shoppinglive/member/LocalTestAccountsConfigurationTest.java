package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.shoppinglive.member.operations.LocalTestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class LocalTestAccountsConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(LocalTestAccounts.class)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));

    @Test
    void onlyExplicitLocalConfigurationEnablesFixtures() {
        runner.withPropertyValues("spring.profiles.active=local", "member.local-test-accounts.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(LocalTestAccounts.class));
        runner.withPropertyValues("spring.profiles.active=local", "member.local-test-accounts.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(LocalTestAccounts.class));
        runner.withPropertyValues("spring.profiles.active=local")
            .run(context -> assertThat(context).doesNotHaveBean(LocalTestAccounts.class));
        for (String profile : new String[] {"dev", "prod", "production", "local,dev", "local,prod", "local,production", "test"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "member.local-test-accounts.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LocalTestAccounts.class));
        }
    }
}
