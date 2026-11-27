package com.shoppinglive.catalog.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** H2 로 확인한다. 같은 테스트를 PostgreSQL 로도 돌리는 판은 {@link ViewRankingPostgresTest} 다. */
class ViewRankingTest {
    private static final Instant NOW = Instant.parse("2026-11-27T10:20:00Z");
    private static final Instant HOUR = Instant.parse("2026-11-27T10:00:00Z");

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private DataSource source;

    /** Flyway 를 적용한 빈 Catalog DB. */
    DataSource catalogDatabase() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:ranking_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        Flyway.configure().dataSource(source).load().migrate();
        return source;
    }

    @BeforeEach
    void prepare() {
        source = catalogDatabase();
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
    }

    private ProductViewCounter counter() {
        return new ProductViewCounter(named, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ViewRanking ranking() {
        return new ViewRanking(
                named,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                new DefaultLockingTaskExecutor(
                        new JdbcTemplateLockProvider(
                                JdbcTemplateLockProvider.Configuration.builder()
                                        .withJdbcTemplate(jdbc)
                                        .usingDbTime()
                                        .build())),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(7));
    }

    private Map<Long, Long> hourly(Instant hour) {
        Map<Long, Long> views = new HashMap<>();
        jdbc.query(
                "SELECT product_id, views FROM product_view_hourly WHERE hour_start = ?",
                rs -> {
                    views.put(rs.getLong("product_id"), rs.getLong("views"));
                },
                Timestamp.from(hour));
        return views;
    }

    private Map<Long, Long> scores() {
        Map<Long, Long> scores = new HashMap<>();
        jdbc.query(
                "SELECT product_id, score FROM product_view_score",
                rs -> {
                    scores.put(rs.getLong("product_id"), rs.getLong("score"));
                });
        return scores;
    }

    private List<Long> indexTasks() {
        return jdbc.queryForList("SELECT product_id FROM index_task ORDER BY id", Long.class);
    }

    private void views(long productId, Instant hour, long views) {
        jdbc.update(
                "INSERT INTO product_view_hourly (product_id, hour_start, views) VALUES (?, ?, ?)",
                productId,
                Timestamp.from(hour),
                views);
    }

    @Test
    @DisplayName("상세 조회수는 메모리에 모았다가 지금 시간 칸에 더하고, 앱이 여러 대여도 같은 칸에 합쳐진다")
    void flushesViewsIntoHourlyCounts() {
        ProductViewCounter first = counter();
        ProductViewCounter second = counter();
        for (int i = 0; i < 3; i++) first.record(1);
        second.record(1);
        second.record(1);
        first.record(2);

        first.flush();
        second.flush();
        assertThat(hourly(HOUR)).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 5L, 2L, 1L));

        // 새 조회가 없으면 바뀌지 않고, 있으면 더한다.
        first.flush();
        first.record(1);
        first.flush();
        assertThat(hourly(HOUR)).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 6L, 2L, 1L));
    }

    @Test
    @DisplayName("여러 앱이 같은 시간 칸을 동시에 처음 만들어도 조회수를 잃지 않는다")
    void concurrentFirstFlushesKeepEveryView() throws Exception {
        int apps = 8;
        ExecutorService pool = Executors.newFixedThreadPool(apps);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> done =
                    java.util.stream.IntStream.range(0, apps)
                            .<Future<?>>mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        ProductViewCounter app = counter();
                                                        app.record(7);
                                                        start.await();
                                                        app.flush();
                                                        return null;
                                                    }))
                            .toList();
            start.countDown();
            for (Future<?> future : done) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(hourly(HOUR)).containsExactlyEntriesOf(Map.of(7L, (long) apps));
    }

    @Test
    @DisplayName("최근 7일 조회수 합으로 점수를 다시 계산하고, 바뀐 상품만 색인 작업을 남기며 오래된 칸은 지운다")
    void recalculatesScoresFromLastSevenDays() {
        Instant since = HOUR.minus(Duration.ofDays(7));
        views(1, HOUR.minus(Duration.ofHours(1)), 10);
        views(2, HOUR.minus(Duration.ofDays(6)), 3);
        views(2, since.minus(Duration.ofHours(1)), 2);
        views(3, since.minus(Duration.ofHours(1)), 7);
        views(5, since, 1);
        jdbc.update("INSERT INTO product_view_score (product_id, score) VALUES (3, 7), (4, 4)");

        assertThat(ranking().recalculate()).isEqualTo(5);

        // 집계 시작 칸은 포함하고, 그보다 오래된 칸은 점수에서 빠지고 지워진다.
        assertThat(scores()).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 10L, 2L, 3L, 5L, 1L));
        assertThat(indexTasks()).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM product_view_hourly WHERE hour_start < ?",
                                Long.class,
                                Timestamp.from(since)))
                .isZero();

        // 다시 계산해도 점수가 같으면 색인 작업을 더 남기지 않는다.
        assertThat(ranking().recalculate()).isZero();
        assertThat(indexTasks()).hasSize(5);
    }
}
