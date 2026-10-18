package com.shoppinglive.live.viewer.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import com.shoppinglive.live.viewer.dto.ViewerTotal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class ViewerPresenceService {
    public static final int EXPIRY_SECONDS = 60;
    private static final DefaultRedisScript<Long> COUNT =
            new DefaultRedisScript<>(
                    """
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - tonumber(ARGV[1]))
            if ARGV[2] ~= '' then redis.call('ZADD', KEYS[1], now, ARGV[2]) end
            local total = redis.call('ZCARD', KEYS[1])
            if total > 0 then redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[1]) * 2) end
            return total
            """,
                    Long.class);
    private final BroadcastRepository broadcasts;
    private final StringRedisTemplate redis;

    public ViewerPresenceService(BroadcastRepository broadcasts, StringRedisTemplate redis) {
        this.broadcasts = broadcasts;
        this.redis = redis;
    }

    public ViewerTotal total(long id) {
        return count(id, null);
    }

    public ViewerTotal heartbeat(long id, UUID viewerId) {
        if (viewerId == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "시청 브라우저 식별자가 필요합니다.");
        return count(id, viewerId);
    }

    private ViewerTotal count(long id, UUID viewerId) {
        var broadcast =
                broadcasts
                        .findById(id)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
        if (broadcast.getStatus() != BroadcastStatus.LIVE) {
            if (viewerId != null)
                throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송에서만 시청 상태를 갱신할 수 있습니다.");
            return new ViewerTotal(id, 0, Instant.now(), EXPIRY_SECONDS);
        }
        try {
            // Redis 시각으로 만료 제거와 갱신·집계를 원자적으로 처리해 pod 간 시계 차이를 피한다.
            Long total =
                    redis.execute(
                            COUNT,
                            List.of("live:viewers:" + id),
                            String.valueOf(EXPIRY_SECONDS * 1000L),
                            viewerId == null ? "" : viewerId.toString());
            if (total == null)
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "시청자 집계 응답이 없습니다.");
            return new ViewerTotal(id, total, Instant.now(), EXPIRY_SECONDS);
        } catch (DataAccessException e) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "시청자 수를 확인하지 못했습니다. 잠시 후 다시 조회하세요.");
        }
    }
}
