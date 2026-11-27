package com.shoppinglive.catalog.ranking;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 상품 상세 조회수를 센다. 조회마다 DB 에 쓰지 않고 메모리에 모았다가 주기마다 시간 단위 칸(product_view_hourly)에 더한다. 라이브로 조회가 몰려도 DB
 * 쓰기는 주기마다 상품 수만큼이다.
 *
 * <p>앱이 갑자기 종료되면 아직 쓰지 않은 조회수는 잃는다. 랭킹 용도라 감수한다. 정상 종료 때는 남은 조회수를 쓰고 끝낸다. 저장에 실패한 조회수는 다음 주기에 다시
 * 쓴다.
 */
public class ProductViewCounter {
    private static final Logger log = LoggerFactory.getLogger(ProductViewCounter.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final Map<Long, AtomicLong> pending = new ConcurrentHashMap<>();

    public ProductViewCounter(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(long productId) {
        pending.computeIfAbsent(productId, id -> new AtomicLong()).incrementAndGet();
    }

    /** 모인 조회수를 지금 시각이 속한 시간 칸에 더한다. 주기 경계에 걸친 조회는 다음 시간 칸에 들어갈 수 있다. */
    @Scheduled(
            initialDelayString = "${catalog.ranking.flush-interval:1m}",
            fixedDelayString = "${catalog.ranking.flush-interval:1m}")
    @PreDestroy
    public void flush() {
        Timestamp hour = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.HOURS));
        pending.forEach(
                (productId, counter) -> {
                    // getAndSet 이라 비우는 사이에 들어온 조회도 잃지 않고 다음 주기로 넘어간다.
                    long views = counter.getAndSet(0);
                    if (views == 0) return;
                    try {
                        add(productId, hour, views);
                    } catch (DataAccessException failure) {
                        counter.addAndGet(views);
                        log.warn(
                                "조회수 저장 실패, 다음 주기에 다시 쓴다: 상품={}, 원인={}",
                                productId,
                                failure.toString());
                    }
                });
    }

    /**
     * 앱이 여러 대면 같은 칸을 동시에 만들 수 있다. 키 충돌이면 다른 앱이 먼저 만든 것이므로 더하기로 바꾼다. 문장마다 바로 커밋되므로 충돌한 INSERT 가 다음
     * UPDATE 를 막지 않는다.
     */
    private void add(long productId, Timestamp hour, long views) {
        var params = Map.<String, Object>of("productId", productId, "hour", hour, "views", views);
        String update =
                """
                UPDATE product_view_hourly SET views = views + :views
                WHERE product_id = :productId AND hour_start = :hour
                """;
        if (jdbc.update(update, params) > 0) return;
        try {
            jdbc.update(
                    """
                    INSERT INTO product_view_hourly (product_id, hour_start, views)
                    VALUES (:productId, :hour, :views)
                    """,
                    params);
        } catch (DuplicateKeyException createdByAnotherApp) {
            jdbc.update(update, params);
        }
    }
}
