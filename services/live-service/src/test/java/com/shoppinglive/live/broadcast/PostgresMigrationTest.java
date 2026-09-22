package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 PostgreSQL 에 Flyway migration 을 적용하고 DB 불변조건을 SQL 로 직접 검증한다.
 * H2 통과는 이 검증의 증거가 되지 못한다. 실행 방법은 application-postgres.yml 주석 참고.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "LIVE_PG_TEST", matches = "1")
@DisplayName("실제 PostgreSQL에서 Flyway 마이그레이션과 DB 불변조건 검증")
class PostgresMigrationTest {

    @Autowired
    DataSource dataSource;

    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE broadcast CASCADE");
    }

    @DisplayName("Flyway 마이그레이션이 적용되어 broadcast 테이블이 생성된다")
    @Test
    void migrationIsApplied() {
        final Integer applied = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'broadcast'",
            Integer.class)).isEqualTo(1);
    }

    @DisplayName("같은 request_key를 중복 INSERT하면 유니크 제약에 걸린다")
    @Test
    void duplicateIdempotencyKeyViolatesUniqueConstraint() {
        insertBroadcast("dup-key", "arn:aws:ivs:channel/a", "PREPARING");
        assertThatThrownBy(() -> insertBroadcast("dup-key", "arn:aws:ivs:channel/b", "PREPARING"))
            .hasMessageContaining("broadcast_request_key_key");
    }

    void insertBroadcast(final String requestKey, final String channelArn, final String status) {
        jdbc.update("""
            INSERT INTO broadcast (created_at, updated_at, request_key, fingerprint, title,
                scheduled_at, channel_arn, playback_url, status, version)
            VALUES (now(), now(), ?, 'fp', 'title', now(), ?, 'https://example/p.m3u8', ?, 0)
            """, requestKey, channelArn, status);
    }
}
