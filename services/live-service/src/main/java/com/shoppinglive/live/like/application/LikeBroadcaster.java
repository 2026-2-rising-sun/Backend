package com.shoppinglive.live.like.application;

import com.shoppinglive.live.like.api.LikeTotalResponse;
import com.shoppinglive.live.stream.application.BroadcastStreamRegistry;
import com.shoppinglive.live.stream.application.StreamEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 좋아요마다 이벤트를 보내지 않는다. 각 pod 가 주기적으로 Redis 합계를 읽고, 바뀐 방송만
 * 자기에게 붙은 연결에 likes.updated 로 전달한다. 좋아요가 몰려도 방송당 주기마다 이벤트는 하나다.
 */
@Component
public class LikeBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(LikeBroadcaster.class);

    private final BroadcastStreamRegistry registry;
    private final StringRedisTemplate redis;
    private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();

    public LikeBroadcaster(final BroadcastStreamRegistry registry, final StringRedisTemplate redis) {
        this.registry = registry;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${live.likes.publish-interval:1s}")
    void publishChanged() {
        final Set<Long> connected = registry.broadcastIds();
        lastSent.keySet().retainAll(connected);
        if (connected.isEmpty()) {
            return;
        }
        try {
            final List<Long> ids = List.copyOf(connected);
            final List<String> totals = redis.opsForValue().multiGet(ids.stream().map(LikeService::key).toList());
            for (int index = 0; index < ids.size(); index++) {
                if (totals.get(index) == null) {
                    continue;
                }
                final long id = ids.get(index);
                final long total = Long.parseLong(totals.get(index));
                final Long previous = lastSent.put(id, total);
                if (previous == null || previous != total) {
                    registry.publish(id, StreamEvent.of("likes.updated", new LikeTotalResponse(id, total)));
                }
            }
        } catch (RuntimeException e) {
            // Redis 장애 중에는 전달이 멈춘다. 1초마다 반복되므로 warn 으로 남기지 않는다.
            log.debug("like totals not published: cause={}", e.toString());
        }
    }
}
