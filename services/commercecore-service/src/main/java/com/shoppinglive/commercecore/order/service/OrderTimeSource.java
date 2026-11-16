package com.shoppinglive.commercecore.order.service;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** JVM 간 시계 차이와 행 잠금 대기 전의 트랜잭션 시각을 주문 기한 판정에 사용하지 않는다. */
@Component
public class OrderTimeSource {
    private final JdbcTemplate jdbc;
    private final String query;

    public OrderTimeSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        String database =
                jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
        query =
                "PostgreSQL".equals(database)
                        ? "SELECT clock_timestamp()"
                        : "SELECT CURRENT_TIMESTAMP";
    }

    public Instant now() {
        return jdbc.queryForObject(query, Timestamp.class).toInstant();
    }
}
