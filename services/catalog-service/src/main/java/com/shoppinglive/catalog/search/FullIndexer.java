package com.shoppinglive.catalog.search;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * Catalog DB 전체로 새 인덱스를 채우고 alias 를 옮긴다. 부분 색인이 놓친 상품을 바로잡고, 매핑을 바꿨을 때 문서를 다시 만든다.
 *
 * <p>예정 시각에 한 번 실행하는 대신 주기적으로 할 일이 있는지 본다. alias 가 없거나(첫 배포) 지금 인덱스를 만든 뒤 예정 시각이 지났으면 다시 만든다. 그래서
 * 예정 시각에 부분 색인이 잠금을 쥐고 있어도 다음 확인 때 실행되고, Pod 가 여러 개여도 잠금 안에서 다시 확인해 한 번만 만든다.
 *
 * <p>잠금 이름이 부분 색인과 같아서 전체 색인 중에는 부분 색인이 돌지 않는다. 그 사이 바뀐 상품은 index_task 에 남아 alias 를 옮긴 뒤 새 인덱스에
 * 반영된다.
 */
public class FullIndexer {
    private static final Logger log = LoggerFactory.getLogger(FullIndexer.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductSearchSource source;
    private final ElasticsearchProductIndex index;
    private final LockingTaskExecutor locks;
    private final CronExpression schedule;
    private final ZoneId zone;
    private final Clock clock;
    private final int batchSize;
    private final Duration lockAtMostFor;

    public FullIndexer(
            NamedParameterJdbcTemplate jdbc,
            ProductSearchSource source,
            ElasticsearchProductIndex index,
            LockingTaskExecutor locks,
            CronExpression schedule,
            ZoneId zone,
            Clock clock,
            int batchSize,
            Duration lockAtMostFor) {
        if (batchSize < 1) throw new IllegalArgumentException("색인 배치 크기는 양수여야 합니다.");
        this.jdbc = jdbc;
        this.source = source;
        this.index = index;
        this.locks = locks;
        this.schedule = schedule;
        this.zone = zone;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lockAtMostFor = lockAtMostFor;
    }

    @Scheduled(
            initialDelayString = "${catalog.search.full.initial-delay:5s}",
            fixedDelayString = "${catalog.search.full.check-interval:1m}")
    public void run() {
        try {
            if (!due()) return;
            locks.executeWithLock(
                    (Runnable)
                            () -> {
                                if (due()) rebuild();
                            },
                    new LockConfiguration(
                            Instant.now(), PartialIndexer.LOCK_NAME, lockAtMostFor, Duration.ZERO));
        } catch (RuntimeException failure) {
            log.warn("전체 색인 실패, 다음 확인 때 다시 시도: 원인={}", failure.toString());
        }
    }

    /** alias 가 없거나, 지금 인덱스를 만든 뒤 예정 시각이 지났으면 다시 만들 때다. */
    boolean due() {
        Optional<String> current = index.currentIndex();
        if (current.isEmpty()) return true;
        ZonedDateTime next =
                schedule.next(ElasticsearchProductIndex.createdAt(current.get()).atZone(zone));
        return next != null && !next.toInstant().isAfter(clock.instant());
    }

    /**
     * 새 인덱스를 상품 id 순서로 나눠 채운 뒤 공개한다. 중간에 실패하면 새 인덱스를 지우고 alias 는 그대로 둔다.
     *
     * @return 새 인덱스의 문서 수
     */
    public int rebuild() {
        String target = index.createIndex();
        try {
            int count = 0;
            long lastId = 0;
            while (true) {
                List<Long> ids =
                        jdbc.queryForList(
                                """
                                SELECT id FROM product
                                WHERE id > :lastId AND deleted_at IS NULL
                                ORDER BY id LIMIT :size
                                """,
                                Map.of("lastId", lastId, "size", batchSize),
                                Long.class);
                if (ids.isEmpty()) break;
                Collection<ProductSearchDocument> documents = source.load(ids).values();
                Set<Long> failed = index.add(target, documents);
                if (!failed.isEmpty())
                    throw new IllegalStateException("전체 색인 중 문서를 넣지 못했습니다: 상품 id=" + failed);
                count += documents.size();
                lastId = ids.get(ids.size() - 1);
            }
            index.publish(target, count);
            log.info("전체 색인 완료: 인덱스={}, 문서 수={}", target, count);
            return count;
        } catch (RuntimeException failure) {
            try {
                index.delete(target);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
}
