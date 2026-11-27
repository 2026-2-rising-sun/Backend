package com.shoppinglive.catalog.ranking;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 1시간마다 최근 {@code window}(7일) 조회수 합으로 랭킹 점수를 다시 계산한다. 점수가 바뀐 상품만 고치고 같은 트랜잭션에서 index_task 를 남겨 ES
 * 문서의 viewScore 를 부분 색인이 고친다. 집계 기간보다 오래된 시간 칸은 지운다.
 *
 * <p>앱이 여러 대여도 ShedLock 으로 한 곳에서만 돈다. 색인 잠금과는 이름이 달라 색인과 동시에 돌 수 있다. index_task 만 남기므로 색인과 겹쳐도 다음
 * 부분 색인이 반영한다.
 */
public class ViewRanking {
    static final String LOCK_NAME = "catalog-view-ranking";
    private static final Logger log = LoggerFactory.getLogger(ViewRanking.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final LockingTaskExecutor locks;
    private final Clock clock;
    private final Duration window;

    public ViewRanking(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transaction,
            LockingTaskExecutor locks,
            Clock clock,
            Duration window) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.locks = locks;
        this.clock = clock;
        this.window = window;
    }

    /** 잠금은 최소 1분 잡아 둔다. 다른 앱의 시계가 조금 늦어 같은 시각에 다시 실행하는 것을 막는다. */
    @Scheduled(
            cron = "${catalog.ranking.cron:0 5 * * * *}",
            zone = "${catalog.ranking.zone:Asia/Seoul}")
    public void run() {
        try {
            locks.executeWithLock(
                    (Runnable) this::recalculate,
                    new LockConfiguration(
                            Instant.now(),
                            LOCK_NAME,
                            Duration.ofMinutes(10),
                            Duration.ofMinutes(1)));
        } catch (RuntimeException failure) {
            log.warn("랭킹 계산 실패, 다음 주기에 다시 계산: 원인={}", failure.toString());
        }
    }

    /**
     * @return 점수가 바뀐 상품 수
     */
    public int recalculate() {
        Integer changed = transaction.execute(status -> recalculateInTransaction());
        log.info("랭킹 계산 완료: 점수가 바뀐 상품 수={}", changed);
        return changed == null ? 0 : changed;
    }

    private int recalculateInTransaction() {
        Instant since = clock.instant().truncatedTo(ChronoUnit.HOURS).minus(window);
        Map<String, Object> range = Map.of("since", Timestamp.from(since));
        Map<Long, Long> next = new HashMap<>();
        jdbc.query(
                """
                SELECT product_id, SUM(views) AS score FROM product_view_hourly
                WHERE hour_start >= :since GROUP BY product_id
                """,
                range,
                rs -> {
                    long score = rs.getLong("score");
                    if (score > 0) next.put(rs.getLong("product_id"), score);
                });
        Map<Long, Long> current = new HashMap<>();
        jdbc.query(
                "SELECT product_id, score FROM product_view_score",
                Map.of(),
                rs -> {
                    current.put(rs.getLong("product_id"), rs.getLong("score"));
                });

        Set<Long> ids = new HashSet<>(current.keySet());
        ids.addAll(next.keySet());
        List<Long> changed =
                ids.stream().filter(id -> !Objects.equals(next.get(id), current.get(id))).toList();
        for (long id : changed) {
            Map<String, Object> row = new HashMap<>();
            row.put("productId", id);
            row.put("score", next.get(id));
            if (!next.containsKey(id))
                jdbc.update("DELETE FROM product_view_score WHERE product_id = :productId", row);
            else if (!current.containsKey(id))
                jdbc.update(
                        "INSERT INTO product_view_score (product_id, score) VALUES (:productId, :score)",
                        row);
            else
                jdbc.update(
                        "UPDATE product_view_score SET score = :score WHERE product_id = :productId",
                        row);
            jdbc.update("INSERT INTO index_task (product_id) VALUES (:productId)", row);
        }
        jdbc.update("DELETE FROM product_view_hourly WHERE hour_start < :since", range);
        return changed.size();
    }
}
