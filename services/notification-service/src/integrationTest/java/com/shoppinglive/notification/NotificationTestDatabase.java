package com.shoppinglive.notification;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 같은 업무 시나리오를 H2와 격리 PostgreSQL에서 실행한다. 클래스별 스키마로 서로의 데이터를 보호한다. */
public final class NotificationTestDatabase {
    private NotificationTestDatabase() {}

    public static DataSource create(String schema) {
        if (!schema.matches("[a-z][a-z0-9_]+"))
            throw new IllegalArgumentException("테스트 스키마 이름이 올바르지 않습니다");
        String url = System.getenv("NOTIFICATION_TEST_POSTGRES_URL");
        if (url == null || url.isBlank()) {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        if (!url.startsWith("jdbc:postgresql:"))
            throw new IllegalArgumentException("격리 PostgreSQL 주소가 필요합니다");
        String user = System.getenv().getOrDefault("NOTIFICATION_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("NOTIFICATION_TEST_DB_PASSWORD", "test");
        var source = new DriverManagerDataSource(url, user, password);
        new JdbcTemplate(source).execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        return new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
    }
}
