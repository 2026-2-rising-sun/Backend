package com.shoppinglive.catalog.snapshot;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_POSTGRES_URL", matches = ".+")
class CatalogSnapshotPostgresTest extends CatalogSnapshotStoreTest {
    @Override
    protected String url() {
        return System.getenv("CATALOG_TEST_POSTGRES_URL");
    }

    @Override
    protected String user() {
        return System.getenv("CATALOG_TEST_POSTGRES_USER");
    }

    @Override
    protected String password() {
        return System.getenv("CATALOG_TEST_POSTGRES_PASSWORD");
    }
}
