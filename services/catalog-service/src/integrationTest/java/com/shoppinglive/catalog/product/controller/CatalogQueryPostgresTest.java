package com.shoppinglive.catalog.product.controller;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_POSTGRES_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogQueryPostgresTest extends CatalogQueryHttpTest {
    static final String SCHEMA = "catalog_query_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry p) {
        String url = System.getenv("CATALOG_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.datasource.username", () -> System.getenv("CATALOG_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("CATALOG_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                System.getenv("CATALOG_TEST_POSTGRES_URL"),
                                System.getenv("CATALOG_TEST_POSTGRES_USER"),
                                System.getenv("CATALOG_TEST_POSTGRES_PASSWORD"));
                var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
