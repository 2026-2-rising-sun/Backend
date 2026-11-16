package com.shoppinglive.catalog.product.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("격리 PostgreSQL 상품 snapshot 버전·동시 수신")
class ProductReplicaPostgresTest extends ProductReplicaRepositoryTest {
    @Override
    protected String databaseUrl() {
        return System.getenv("CATALOG_TEST_POSTGRES_URL");
    }

    @Override
    protected String databaseUser() {
        return System.getenv("CATALOG_TEST_POSTGRES_USER");
    }

    @Override
    protected String databasePassword() {
        return System.getenv("CATALOG_TEST_POSTGRES_PASSWORD");
    }
}
