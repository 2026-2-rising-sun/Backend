package com.shoppinglive.live.stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 방송별로 초당 실시간 전달할 일반 채팅 수를 센다. pod 가 여러 대라 방송 전체 속도는 Redis 에서 센다. 초마다 키를 새로 만들어 INCR 하고 처음 만든 키에만
 * EXPIRE 를 건다(Redis INCR 문서의 rate limiter Lua 패턴). 예산을 넘은 채팅도 저장은 되며 실시간 전달만 생략한다.
 *
 * <p>MULTI/EXEC 대신 Lua 스크립트를 쓴다. Lettuce 공유 연결은 트랜잭션이 아닌 명령에만 쓰이고, 풀이 없으면 트랜잭션마다 연결을 새로 연다. 채팅마다
 * Redis 연결을 새로 열어 초당 수백 건에서 연결이 밀렸다(부하 테스트). 스크립트는 한 명령으로 원자적으로 실행된다.
 */
@Component
public class ChatRelayBudget {
    private static final Logger log = LoggerFactory.getLogger(ChatRelayBudget.class);
    private static final Duration KEY_TTL = Duration.ofSeconds(10);
    private static final RedisScript<Long> INCREMENT =
            RedisScript.of(
                    """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                      redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return count""",
                    Long.class);

    private final StringRedisTemplate redis;
    private final StreamProperties properties;
    private final Counter skipped;
    private final Counter uncounted;

    public ChatRelayBudget(
            final StringRedisTemplate redis,
            final StreamProperties properties,
            final MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.skipped = meters.counter("shoppinglive.live.chat.relay.skipped");
        this.uncounted = meters.counter("shoppinglive.live.chat.relay.budget.uncounted");
    }

    /** 예산 안이면 true. Redis 로 셀 수 없으면 생략하지 않고 uncounted 메트릭에 남긴다. 그때는 어차피 발행도 실패하기 쉽다. */
    public boolean tryAcquire(final long broadcastId, final Instant now) {
        final String key = "live:chat-budget:" + broadcastId + ":" + now.getEpochSecond();
        final Long count;
        try {
            count = redis.execute(INCREMENT, List.of(key), String.valueOf(KEY_TTL.toSeconds()));
        } catch (DataAccessException e) {
            uncounted.increment();
            log.debug("채팅 중계 사용량 집계 실패: 방송 ID={} 원인={}", broadcastId, e.toString());
            return true;
        }
        if (count == null || count <= properties.chatBudgetPerSecond()) {
            return true;
        }
        skipped.increment();
        return false;
    }
}
