package com.shoppinglive.commercecore.product.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class ProductSchemaPostgresTest extends ProductSchemaMigrationTest {
    @Override
    protected String databaseUrl() {
        return System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
    }

    @Override
    protected String databaseUser() {
        return System.getenv("COMMERCECORE_TEST_POSTGRES_USER");
    }

    @Override
    protected String databasePassword() {
        return System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD");
    }
}
