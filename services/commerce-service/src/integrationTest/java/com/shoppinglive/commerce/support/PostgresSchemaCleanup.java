package com.shoppinglive.commerce.support;

import java.sql.DriverManager;

/** Gradle 종료 작업은 테스트 실패 후에도 실행되며, 이번 실행이 생성한 임의 스키마만 삭제할 수 있다. */
public final class PostgresSchemaCleanup {
    private PostgresSchemaCleanup() {}

    public static void main(String[] args) throws Exception {
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        if (url == null || url.isBlank()) {
            return;
        } // 테스트 작업에서 URL 누락을 이미 거부한다.
        String schema = System.getProperty("commerce.tests.schema", "");
        if (!schema.matches("commerce_test_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("현재 테스트 실행이 생성하지 않은 스키마는 정리할 수 없습니다.");
        }
        try (var connection =
                        DriverManager.getConnection(
                                url,
                                System.getenv()
                                        .getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
                                System.getenv()
                                        .getOrDefault(
                                                "COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            System.out.println("Removed isolated test schema: " + schema);
        }
    }
}
