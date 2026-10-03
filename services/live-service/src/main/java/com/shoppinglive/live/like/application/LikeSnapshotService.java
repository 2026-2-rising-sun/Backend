package com.shoppinglive.live.like.application;

import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Redis 의 좋아요 합계를 DB 에 주기적으로 보관한다. 보관본은 더 큰 값으로만 바뀐다.
 * 그래서 여러 pod 가 동시에, 여러 번 실행해도 합계가 줄지 않는다.
 */
@Service
public class LikeSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(LikeSnapshotService.class);
    // 종료 직후의 보관이 실패해도 이 시간 동안은 주기 작업이 다시 보관한다.
    private static final Duration ENDED_WINDOW = Duration.ofHours(1);

    private final BroadcastRepository broadcasts;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public LikeSnapshotService(final BroadcastRepository broadcasts, final StringRedisTemplate redis,
                               final JdbcTemplate jdbc) {
        this.broadcasts = broadcasts;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    // ponytail: 모든 pod 가 같은 방송을 각자 보관한다. 결과는 같고 중복 쓰기만 생긴다. 부담이 측정되면 한 pod 만 돌게 한다.
    /** 한 방송의 실패가 다른 방송의 보관을 막지 않는다. 로그는 주기당 한 줄이다. */
    @Scheduled(fixedDelayString = "${live.likes.snapshot-interval:10s}")
    public void snapshotAll() {
        int failed = 0;
        RuntimeException first = null;
        try {
            for (final long broadcastId : broadcasts.findIdsForLikeSnapshot(Instant.now().minus(ENDED_WINDOW))) {
                try {
                    snapshot(broadcastId);
                } catch (RuntimeException e) {
                    failed++;
                    first = first == null ? e : first;
                }
            }
        } catch (RuntimeException e) {
            failed++;
            first = e;
        }
        if (failed > 0) {
            log.warn("like snapshot failed for {} broadcast(s), will retry next cycle: first cause={}", failed,
                first.toString());
        }
    }

    /** Redis 에 값이 없으면 보관할 것이 없다. */
    public void snapshot(final long broadcastId) {
        final String value = redis.opsForValue().get(LikeService.key(broadcastId));
        if (value != null) {
            store(broadcastId, Long.parseLong(value));
        }
    }

    /** 기존 값보다 클 때만 쓴다. */
    public void store(final long broadcastId, final long total) {
        if (raise(broadcastId, total)) {
            return;
        }
        try {
            jdbc.update("""
                INSERT INTO broadcast_like_snapshot (broadcast_id, total, updated_at)
                SELECT CAST(? AS BIGINT), CAST(? AS BIGINT), CAST(? AS TIMESTAMP WITH TIME ZONE)
                WHERE NOT EXISTS (SELECT 1 FROM broadcast_like_snapshot WHERE broadcast_id = ?)
                """, broadcastId, total, Timestamp.from(Instant.now()), broadcastId);
        } catch (DuplicateKeyException e) {
            // 다른 pod 가 같은 순간에 첫 행을 넣었다. 그 값이 더 작으면 이 값으로 올린다.
            raise(broadcastId, total);
        }
    }

    private boolean raise(final long broadcastId, final long total) {
        return jdbc.update(
            "UPDATE broadcast_like_snapshot SET total = ?, updated_at = ? WHERE broadcast_id = ? AND total < ?",
            total, Timestamp.from(Instant.now()), broadcastId, total) == 1;
    }
}
