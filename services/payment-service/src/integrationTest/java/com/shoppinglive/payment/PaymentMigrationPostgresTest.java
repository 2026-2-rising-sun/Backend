package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class PaymentMigrationPostgresTest {
    @Test
    @DisplayName("빈 스키마에 Payment 마이그레이션 전체가 실패 없이 적용된다")
    void allMigrationsApplyToEmptySchema() {
        String schema = "payment_migration_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        var flyway =
                Flyway.configure()
                        .locations("classpath:db/payment-migration")
                        .dataSource(
                                new DriverManagerDataSource(
                                        url,
                                        System.getenv("PAYMENT_TEST_POSTGRES_USER"),
                                        System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD")))
                        .schemas(schema)
                        .defaultSchema(schema)
                        .cleanDisabled(false)
                        .load();
        try {
            assertThat(flyway.migrate().success).isTrue();
            assertThat(flyway.info().pending()).isEmpty();
        } finally {
            flyway.clean();
        }
    }
}
