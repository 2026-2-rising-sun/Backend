package com.shoppinglive.live.viewer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Tag("redis")
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
class ViewerPresenceRedisTest {
    @Test
    @DisplayName("공유 Redis에서 브라우저 중복·방송 분리·이탈 만료·종료 집계를 검증한다")
    void sharesCountAcrossInstancesAndRemovesExpiredViewers() {
        var connection =
                new LettuceConnectionFactory(
                        "127.0.0.1",
                        Integer.parseInt(System.getenv().getOrDefault("LIVE_REDIS_PORT", "56379")));
        connection.afterPropertiesSet();
        var redis = new StringRedisTemplate(connection);
        long firstId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        long secondId = firstId - 1;
        String key = "live:viewers:" + firstId;
        String otherKey = "live:viewers:" + secondId;
        try {
            var broadcasts = mock(BroadcastRepository.class);
            var broadcast = mock(Broadcast.class);
            when(broadcast.getStatus()).thenReturn(BroadcastStatus.LIVE);
            when(broadcasts.findById(firstId)).thenReturn(Optional.of(broadcast));
            when(broadcasts.findById(secondId)).thenReturn(Optional.of(broadcast));
            var firstPod = new ViewerPresenceService(broadcasts, redis);
            var secondPod = new ViewerPresenceService(broadcasts, redis);
            UUID browser = UUID.randomUUID();
            assertThat(firstPod.heartbeat(firstId, browser).total()).isEqualTo(1);
            assertThat(secondPod.heartbeat(firstId, browser).total()).isEqualTo(1);
            assertThat(secondPod.heartbeat(firstId, UUID.randomUUID()).total()).isEqualTo(2);
            assertThat(firstPod.total(firstId).total()).isEqualTo(2);
            assertThat(firstPod.total(secondId).total()).isZero();
            redis.opsForZSet().add(key, UUID.randomUUID().toString(), 0);
            assertThat(secondPod.total(firstId).total()).isEqualTo(2);
            assertThat(redis.opsForZSet().zCard(key)).isEqualTo(2);
            assertThat(redis.getExpire(key)).isBetween(1L, 120L);
            when(broadcast.getStatus()).thenReturn(BroadcastStatus.ENDED);
            assertThat(firstPod.total(firstId).total()).isZero();
        } finally {
            redis.delete(key);
            redis.delete(otherKey);
            connection.destroy();
        }
    }
}
