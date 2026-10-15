package com.shoppinglive.member.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class LocalTestAccountsConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(LocalTestAccounts.class)
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                    .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));

    @DisplayName("명시적인 local 설정이 있을 때만 테스트 계정을 활성화한다")
    @Test
    void onlyExplicitLocalConfigurationEnablesFixtures() {
        runner.withPropertyValues(
                        "spring.profiles.active=local", "member.local-test-accounts.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LocalTestAccounts.class));
        runner.withPropertyValues(
                        "spring.profiles.active=local", "member.local-test-accounts.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LocalTestAccounts.class));
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).doesNotHaveBean(LocalTestAccounts.class));
        for (String profile :
                new String[] {
                    "dev",
                    "prod",
                    "production",
                    "local,dev",
                    "local,prod",
                    "local,production",
                    "test"
                }) {
            runner.withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "member.local-test-accounts.enabled=true")
                    .run(context -> assertThat(context).doesNotHaveBean(LocalTestAccounts.class));
        }
    }
}
