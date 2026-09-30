package com.shoppinglive.commerce.support;

import java.sql.DriverManager;

/** Gradle finalizer also runs after failing tests. It can only drop this invocation's random schema. */
public final class PostgresSchemaCleanup {
    private PostgresSchemaCleanup() {}
    public static void main(String[] args) throws Exception {
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        if (url == null || url.isBlank()) return; // The test task already rejects a missing URL.
        String schema = System.getProperty("commerce.tests.schema", "");
        if (!schema.matches("commerce_test_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Refusing to clean a schema not owned by this test invocation");
        }
        try (var connection = DriverManager.getConnection(url,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            System.out.println("Removed isolated test schema: " + schema);
        }
    }
}
