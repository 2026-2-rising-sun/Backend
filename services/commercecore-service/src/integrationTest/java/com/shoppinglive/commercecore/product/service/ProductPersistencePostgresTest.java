package com.shoppinglive.commercecore.product.service;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("격리 PostgreSQL 상품 원본·Outbox 원자성·동시 등록")
class ProductPersistencePostgresTest extends ProductPersistenceTest {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        String user = System.getenv("COMMERCECORE_TEST_POSTGRES_USER");
        String password = System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD");
        String schema = "product_persistence_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        properties.add("spring.datasource.url", () -> url + "?currentSchema=" + schema);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
    }
}
