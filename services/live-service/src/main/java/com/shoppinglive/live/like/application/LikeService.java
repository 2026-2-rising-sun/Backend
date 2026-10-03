package com.shoppinglive.live.like.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import com.shoppinglive.live.like.api.LikeTotalResponse;
import com.shoppinglive.live.like.domain.BroadcastLikeSnapshot;
import com.shoppinglive.live.like.infrastructure.BroadcastLikeSnapshotRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 실시간 합계는 Redis 에 둔다. 여러 pod 가 같은 key 를 INCR 하므로 잠금 없이 증가분이 모두 합산된다.
 * DB 보관본은 Redis 값이 없을 때의 시작값과 Redis 장애 시 조회값으로 쓴다.
 */
@Service
public class LikeService {
    private static final Logger log = LoggerFactory.getLogger(LikeService.class);
    // 방송 종료 뒤에도 조회와 마지막 보관이 끝날 때까지 값이 남아 있게 한다.
    private static final Duration TTL = Duration.ofDays(7);

    private final BroadcastRepository broadcasts;
    private final BroadcastLikeSnapshotRepository snapshots;
    private final StringRedisTemplate redis;

    public LikeService(final BroadcastRepository broadcasts, final BroadcastLikeSnapshotRepository snapshots,
                       final StringRedisTemplate redis) {
        this.broadcasts = broadcasts;
        this.snapshots = snapshots;
        this.redis = redis;
    }

    public static String key(final long broadcastId) {
        return "live:broadcast:" + broadcastId + ":likes";
    }

    /**
     * 방송 상태는 잠금 없이 읽으므로 종료와 동시에 도착한 좋아요는 반영될 수 있다. 이는 허용한 동작이다.
     * SET NX 는 Redis 에 값이 없을 때만 DB 보관본으로 시작값을 만든다. 이미 있는 값은 덮어쓰지 않는다.
     */
    public LikeTotalResponse add(final long broadcastId) {
        final Broadcast broadcast = broadcasts.findById(broadcastId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
        if (broadcast.getStatus() != BroadcastStatus.LIVE) {
            throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송이 아닙니다.");
        }
        // ponytail: 요청마다 DB 를 두 번(방송·보관본) 읽는다. 부하 측정 후 필요하면 key 존재 확인이나 상태 cache 로 줄인다.
        final long stored = storedTotal(broadcastId);
        try {
            final String key = key(broadcastId);
            redis.opsForValue().setIfAbsent(key, String.valueOf(stored));
            final Long total = redis.opsForValue().increment(key);
            redis.expire(key, TTL);
            return new LikeTotalResponse(broadcastId, total);
        } catch (RuntimeException e) {
            log.warn("like not counted: broadcastId={} cause={}", broadcastId, e.toString());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "좋아요를 처리할 수 없습니다.");
        }
    }

    /** Redis 를 읽을 수 없거나 값이 없으면 DB 보관본으로 응답한다. 보관본이 없으면 0 이다. */
    public LikeTotalResponse total(final long broadcastId) {
        if (!broadcasts.existsById(broadcastId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다.");
        }
        try {
            final String value = redis.opsForValue().get(key(broadcastId));
            if (value != null) {
                return new LikeTotalResponse(broadcastId, Long.parseLong(value));
            }
        } catch (RuntimeException e) {
            log.warn("like total read from snapshot: broadcastId={} cause={}", broadcastId, e.toString());
        }
        return new LikeTotalResponse(broadcastId, storedTotal(broadcastId));
    }

    private long storedTotal(final long broadcastId) {
        return snapshots.findById(broadcastId).map(BroadcastLikeSnapshot::getTotal).orElse(0L);
    }
}
