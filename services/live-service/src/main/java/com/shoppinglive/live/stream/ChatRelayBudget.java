package com.shoppinglive.live.stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 방송별로 초당 실시간 전달할 일반 채팅 수를 센다. pod 가 여러 대라 방송 전체 속도는 Redis 에서 센다. 초마다 키를 새로 만들어 INCR 하고 EXPIRE 로
 * 지운다(Redis INCR 문서의 rate limiter 1 패턴). 예산을 넘은 채팅도 저장은 되며 실시간 전달만 생략한다.
 */
@Component
public class ChatRelayBudget {
    private static final Logger log = LoggerFactory.getLogger(ChatRelayBudget.class);
    private static final Duration KEY_TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final StreamProperties properties;
    private final Counter skipped;

    public ChatRelayBudget(
            final StringRedisTemplate redis,
            final StreamProperties properties,
            final MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.skipped = meters.counter("shoppinglive.live.chat.relay.skipped");
    }

    /** 예산 안이면 true. Redis 로 셀 수 없으면 생략하지 않는다. 그때는 어차피 발행도 실패한다. */
    public boolean tryAcquire(final long broadcastId, final Instant now) {
        final String key = "live:chat-budget:" + broadcastId + ":" + now.getEpochSecond();
        final long count;
        try {
            count = increment(key);
        } catch (DataAccessException e) {
            log.debug(
                    "chat budget not counted: broadcastId={} cause={}", broadcastId, e.toString());
            return true;
        }
        if (count <= properties.chatBudgetPerSecond()) {
            return true;
        }
        skipped.increment();
        return false;
    }

    private long increment(final String key) {
        final List<Object> results =
                redis.execute(
                        new SessionCallback<>() {
                            @Override
                            @SuppressWarnings("unchecked")
                            public List<Object> execute(final RedisOperations operations) {
                                operations.multi();
                                operations.opsForValue().increment(key);
                                operations.expire(key, KEY_TTL);
                                return operations.exec();
                            }
                        });
        return (Long) results.getFirst();
    }
}
