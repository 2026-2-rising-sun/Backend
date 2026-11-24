package com.shoppinglive.catalog.search;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * index_task 에 쌓인 상품을 ES 에 반영한다.
 *
 * <p>상품마다 읽은 시점의 마지막 task id 까지만 지운다. 색인하는 동안 같은 상품이 다시 바뀌면 그 task 는 남아 다음 주기에 처리된다. ES 가 실패한 상품은
 * 지우지 않아서 ES 장애가 끝나면 다시 반영된다. 잠금 이름은 전체 색인과 같아서 두 색인이 동시에 돌지 않는다.
 */
public class PartialIndexer {
    public static final String LOCK_NAME = "catalog-search-index";
    private static final Logger log = LoggerFactory.getLogger(PartialIndexer.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductSearchSource source;
    private final ProductSearchIndex index;
    private final LockingTaskExecutor locks;
    private final int batchSize;
    private final Duration lockAtMostFor;

    public PartialIndexer(
            NamedParameterJdbcTemplate jdbc,
            ProductSearchSource source,
            ProductSearchIndex index,
            LockingTaskExecutor locks,
            int batchSize,
            Duration lockAtMostFor) {
        if (batchSize < 1) throw new IllegalArgumentException("색인 배치 크기는 양수여야 합니다.");
        this.jdbc = jdbc;
        this.source = source;
        this.index = index;
        this.locks = locks;
        this.batchSize = batchSize;
        this.lockAtMostFor = lockAtMostFor;
    }

    @Scheduled(
            initialDelayString = "${catalog.search.partial.initial-delay:10s}",
            fixedDelayString = "${catalog.search.partial.interval:5s}")
    public void run() {
        locks.executeWithLock(
                (Runnable) this::indexOnce,
                new LockConfiguration(Instant.now(), LOCK_NAME, lockAtMostFor, Duration.ZERO));
    }

    /**
     * @return ES 에 반영하고 task 를 지운 상품 수
     */
    public int indexOnce() {
        List<Task> tasks =
                jdbc.query(
                        """
                        SELECT product_id, MAX(id) AS last_id FROM index_task
                        GROUP BY product_id ORDER BY MIN(id) LIMIT :size
                        """,
                        Map.of("size", batchSize),
                        (rs, n) -> new Task(rs.getLong("product_id"), rs.getLong("last_id")));
        if (tasks.isEmpty()) return 0;
        Map<Long, ProductSearchDocument> documents =
                source.load(tasks.stream().map(Task::productId).toList());
        List<Long> deletes = new ArrayList<>();
        for (Task task : tasks)
            if (!documents.containsKey(task.productId())) deletes.add(task.productId());
        Set<Long> failed;
        try {
            failed = index.write(documents.values(), deletes);
        } catch (RuntimeException failure) {
            log.warn("부분 색인 실패, 다음 주기에 다시 처리: 상품 수={}, 원인={}", tasks.size(), failure.toString());
            return 0;
        }
        int done = 0;
        for (Task task : tasks) {
            if (failed.contains(task.productId())) continue;
            jdbc.update(
                    "DELETE FROM index_task WHERE product_id = :productId AND id <= :lastId",
                    Map.of("productId", task.productId(), "lastId", task.lastId()));
            done++;
        }
        if (!failed.isEmpty()) log.warn("부분 색인 일부 실패, 다음 주기에 다시 처리: 상품 id={}", failed);
        return done;
    }

    private record Task(long productId, long lastId) {}
}
