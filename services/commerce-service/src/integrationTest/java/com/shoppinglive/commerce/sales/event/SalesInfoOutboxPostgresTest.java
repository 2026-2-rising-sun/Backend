package com.shoppinglive.commerce.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SalesInfoOutboxPostgresTest extends SalesInfoEventIntegrationTest {
    private static final String SCHEMA =
            "sales_outbox_" + UUID.randomUUID().toString().replace("-", "");

    private static String databaseUrl() {
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", SalesInfoOutboxPostgresTest::databaseUrl);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add(
                "spring.datasource.username", () -> System.getenv("COMMERCE_TEST_POSTGRES_USER"));
        properties.add(
                "spring.datasource.password",
                () -> System.getenv("COMMERCE_TEST_POSTGRES_PASSWORD"));
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @AfterAll
    static void dropOwnedSchema() {
        var source =
                new DriverManagerDataSource(
                        databaseUrl(),
                        System.getenv("COMMERCE_TEST_POSTGRES_USER"),
                        System.getenv("COMMERCE_TEST_POSTGRES_PASSWORD"));
        Flyway.configure().dataSource(source).schemas(SCHEMA).cleanDisabled(false).load().clean();
    }

    @Test
    @DisplayName("판매정보와 Outbox를 함께 커밋하고 기록 실패 시 가격과 버전도 롤백한다")
    void commitsSnapshotAndRollsBackBusinessChangeWhenOutboxFails() {
        long product = UUID.randomUUID().getMostSignificantBits() >>> 1;
        shopping.register(
                new com.shoppinglive.commerce.client.shopping.ProductSnapshot(
                        product, "Outbox 원자성 검증", null));
        long id = registration.register(product, 1000L, 3).getId();
        fixtureSales.add(id);
        assertThat(count(id)).isEqualTo(1);
        sales.changePrice(id, 2000L);
        assertThat(count(id)).isEqualTo(2);
        sales.changePrice(id, 2000L);
        assertThat(count(id)).isEqualTo(2);
        doThrow(new IllegalStateException("검증용 Outbox 저장 실패"))
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<com.shoppinglive.common.outbox.EventOutbox>
                                        getUltimateTargetObject(outbox))
                .append(eq(SalesInfoEventContract.TOPIC_NAME), any());
        assertThatThrownBy(() -> sales.changePrice(id, 3000L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT price FROM sales_info WHERE id=?", Long.class, id))
                .isEqualTo(2000);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_version FROM sales_info WHERE id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        assertThat(count(id)).isEqualTo(2);
    }

    private int count(long salesId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE topic=? AND message_key=?",
                Integer.class,
                SalesInfoEventContract.TOPIC_NAME,
                Long.toString(salesId));
    }
}
